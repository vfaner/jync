package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * docker 实测矩阵 ①:MySQL 8 binlog CDC → OpenGauss 5.0(信创目标)。
 *
 * <p>Runs against the family's shared real MySQL 8 source; only the target is swapped to
 * the shared OpenGauss container ({@link OpenGaussSupport}). Scenarios, per the user's
 * acceptance brief: baseline + streamed INSERT/UPDATE/DELETE, stop/restart resume with a
 * canary that distinguishes resume from a sneaky re-baseline, MySQL-special type matrix
 * (JSON/ENUM/SET/YEAR/BIT/TINYINT(1)/UNSIGNED), an XA transaction, one large
 * multi-batch transaction, and ADD COLUMN while the consumer is down.
 */
class MysqlToOpenGaussIT extends CdcMySqlContainerHarnessIT {

    private static final Logger log = LoggerFactory.getLogger(MysqlToOpenGaussIT.class);

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Override
    protected Connection targetJdbc() throws java.sql.SQLException {
        return OpenGaussSupport.jdbc();
    }

    @Override
    protected DatabaseConfig targetConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("cdc-it-og-tgt");
        config.setType(DatabaseType.OPENGAUSS);
        config.setRole(ConnectionRole.TARGET);
        config.setHost(OpenGaussSupport.host());
        config.setPort(OpenGaussSupport.port());
        config.setDatabaseName(OpenGaussSupport.DATABASE);
        config.setSchemaName("public");
        config.setUsername(OpenGaussSupport.USER);
        config.setPassword(cryptoUtil.encrypt(OpenGaussSupport.PASSWORD));
        return databaseConfigRepository.save(config);
    }

    private SyncConfig cdcConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }

    @Test
    void baselineStreamingAndResumeAfterRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_orders");
            st.execute("CREATE TABLE og_orders (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount DECIMAL(10,2))");
            for (int i = 1; i <= 200; i++) {
                st.execute("INSERT INTO og_orders VALUES (" + i + ", 'seed-" + i + "', "
                        + i + ".5)");
            }
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_orders");
            st.execute("CREATE TABLE og_orders (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount NUMERIC(10,2))");
        }
        Project project = newProject("og-basic", cdcConfig("og_orders"));
        manager.ensureStarted(project);

        waitFor("baseline done", 90_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("200 seeded rows", 60_000, () -> countTarget("og_orders") == 200);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO og_orders VALUES (201, 'streamed', 1.00)");
            st.execute("UPDATE og_orders SET name = 'changed' WHERE id = 7");
            st.execute("DELETE FROM og_orders WHERE id = 13");
            st.execute("UPDATE og_orders SET id = 300 WHERE id = 20");
        }
        waitFor("stream applied", 60_000, () -> countTarget("og_orders") == 200
                && queryTarget("SELECT id FROM og_orders WHERE id = 300").size() == 1);
        assertThat(queryTarget("SELECT name FROM og_orders WHERE id = 7")
                .get(0).get("name")).isEqualTo("changed");
        assertThat(queryTarget("SELECT amount::text a FROM og_orders WHERE id = 1")
                .get(0).get("a")).isEqualTo("1.50");

        // 断点续传:停消费 → 目标删一行作金丝雀 + 源端再写 → 重启。若消费者重跑基线,
        // 金丝雀行会被灌回来;续传则只回放新事件,金丝雀保持缺失。
        manager.ensureStopped(project.getId());
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DELETE FROM og_orders WHERE id = 5");
        }
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO og_orders VALUES (400, 'after-stop', 2.00)");
        }
        manager.ensureStarted(reload(project));
        waitFor("post-restart row", 60_000, () -> countTarget("og_orders") == 200
                && queryTarget("SELECT id FROM og_orders WHERE id = 400").size() == 1
                && queryTarget("SELECT id FROM og_orders WHERE id = 5").isEmpty());
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void mysqlSafeTypeMatrixReachesOpenGauss() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_types");
            st.execute("CREATE TABLE og_types (id INT PRIMARY KEY,"
                    + " c_varchar VARCHAR(64), c_dec DECIMAL(30,10), c_neg INT,"
                    + " c_big BIGINT, c_dt DATETIME(6), c_date DATE, c_time TIME,"
                    + " c_double DOUBLE, c_txt TEXT, c_bin VARBINARY(32))");
            st.execute("INSERT INTO og_types VALUES (1,"
                    + " '中文 emoji 🎉', 12345.6789012345, -42, 9223372036854775806,"
                    + " '2026-10-13 12:34:56.789012', '2026-02-28', '12:34:56',"
                    + " 3.141592653589793, REPEAT('长文本', 100), X'DEADBEEF')");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_types");
            st.execute("CREATE TABLE og_types (id INT PRIMARY KEY,"
                    + " c_varchar VARCHAR(64), c_dec NUMERIC(30,10), c_neg INT,"
                    + " c_big BIGINT, c_dt TIMESTAMP(6), c_date DATE, c_time TIME,"
                    + " c_double DOUBLE PRECISION, c_txt TEXT, c_bin BYTEA)");
        }
        Project project = newProject("og-types", cdcConfig("og_types"));
        manager.ensureStarted(project);
        waitFor("type row applied", 90_000, () -> countTarget("og_types") == 1);

        // No length function at all: the stock opengauss 5.0.0 image initializes the
        // database as SQL_ASCII (verified: server_encoding='SQL_ASCII'), where length/
        // char_length/octet_length all count BYTES. REPEAT('长文本',100) is 300 chars =
        // 900 UTF-8 bytes; the bytes themselves round-trip verbatim (c_varchar's Chinese
        // + emoji assertion proves that). VARCHAR(n) on such a target is likewise
        // byte-budgeted, a deployment gotcha worth documenting rather than papering over.
        Map<String, Object> row = queryTarget("SELECT c_varchar, c_dec::text d, c_neg,"
                + " c_big, to_char(c_dt, 'YYYY-MM-DD HH24:MI:SS.US') dt,"
                + " c_date::text da, c_time::text t, c_double, octet_length(c_txt) tl,"
                + " encode(c_bin, 'hex') bh FROM og_types WHERE id = 1").get(0);
        assertThat(row.get("c_varchar")).isEqualTo("中文 emoji 🎉");
        assertThat((String) row.get("d")).startsWith("12345.6789012345");
        assertThat(row.get("c_neg")).isEqualTo(-42);
        assertThat(row.get("c_big")).isEqualTo(9223372036854775806L);
        assertThat(row.get("dt")).isEqualTo("2026-10-13 12:34:56.789012");
        // openGauss A-compatibility DATE carries a time part, so ::text renders the
        // midnight; the calendar date is what a MySQL DATE means and is preserved.
        assertThat((String) row.get("da")).startsWith("2026-02-28");
        assertThat((String) row.get("t")).startsWith("12:34:56");
        assertThat((Double) row.get("c_double")).isEqualTo(3.141592653589793);
        assertThat(row.get("tl")).isEqualTo(900);
        assertThat(row.get("bh")).isEqualTo("deadbeef");
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void mysqlSpecificTypesReachOpenGauss() throws Exception {
        // MySQL 特有类型:正是「特别字段导致没有识别/转换失败/精度对不上」的怀疑对象。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_mytypes");
            st.execute("CREATE TABLE og_mytypes (id INT PRIMARY KEY,"
                    + " c_year YEAR, c_json JSON, c_enum ENUM('a','b','c'),"
                    + " c_set SET('x','y','z'), c_bit BIT(8), c_tiny TINYINT(1),"
                    + " c_ubig BIGINT UNSIGNED)");
            st.execute("INSERT INTO og_mytypes VALUES (1, 2026,"
                    + " '{\"k\": 1, \"s\": \"中文\"}', 'b', 'x,z', b'10101010', 1,"
                    + " 18446744073709551615)");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            // 预期映射:YEAR→INT,JSON/ENUM/SET→TEXT,BIT→SMALLINT,TINYINT(1)→BOOLEAN
            // (Connector/J tinyInt1isBit 默认),UNSIGNED BIGINT→NUMERIC(20)。
            st.execute("DROP TABLE IF EXISTS og_mytypes");
            st.execute("CREATE TABLE og_mytypes (id INT PRIMARY KEY,"
                    + " c_year INT, c_json TEXT, c_enum VARCHAR(8),"
                    + " c_set VARCHAR(16), c_bit SMALLINT, c_tiny BOOLEAN,"
                    + " c_ubig NUMERIC(20))");
        }
        Project project = newProject("og-mytypes", cdcConfig("og_mytypes"));
        manager.ensureStarted(project);
        waitFor("mysql-specific row applied", 90_000, () -> countTarget("og_mytypes") == 1);

        Map<String, Object> row = queryTarget("SELECT c_year, c_json, c_enum, c_set,"
                + " c_bit, c_tiny, c_ubig::text u FROM og_mytypes WHERE id = 1").get(0);
        assertThat(row.get("c_year")).isEqualTo(2026);
        assertThat((String) row.get("c_json")).contains("\"k\": 1").contains("中文");
        assertThat(row.get("c_enum")).isEqualTo("b");
        assertThat(row.get("c_set")).isEqualTo("x,z");
        // openGauss JDBC returns int2 columns as Integer (PostgreSQL returns Short);
        // compare the numeric value rather than the carrier type.
        assertThat(((Number) row.get("c_bit")).intValue()).isEqualTo(0b10101010);
        assertThat(row.get("c_tiny")).isEqualTo(true);
        assertThat(row.get("u")).isEqualTo("18446744073709551615");
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void xaAndLargeTransactionsApplyWholly() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_tx");
            st.execute("CREATE TABLE og_tx (id BIGINT PRIMARY KEY, v VARCHAR(32))");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_tx");
            st.execute("CREATE TABLE og_tx (id BIGINT PRIMARY KEY, v VARCHAR(32))");
        }
        Project project = newProject("og-tx", cdcConfig("og_tx"));
        manager.ensureStarted(project);
        waitFor("baseline done", 90_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));

        // XA 事务:binlog 里 XA START/END/PREPARE/COMMIT 形态,行必须在 XID 时刻才落库。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("XA START 'jync-xa-1'");
            st.execute("INSERT INTO og_tx VALUES (1, 'xa-row')");
            st.execute("XA END 'jync-xa-1'");
            st.execute("XA PREPARE 'jync-xa-1'");
            st.execute("XA COMMIT 'jync-xa-1'");
        }
        waitFor("XA row applied", 60_000, () -> countTarget("og_tx") == 1);

        // 跨 batch 大事务:单事务 5000 行,全部落库且恰好一次。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("SET autocommit=0");
            for (int i = 2; i <= 5001; i++) {
                st.execute("INSERT INTO og_tx VALUES (" + i + ", 'bulk-" + i + "')");
            }
            st.execute("COMMIT");
        }
        waitFor("5000-row transaction applied", 120_000, () -> countTarget("og_tx") == 5001);
        assertThat(queryTarget("SELECT COUNT(DISTINCT id) c FROM og_tx").get(0).get("c"))
                .isEqualTo(5001L);
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void ddlAddColumnWhileStoppedThenRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_ddl");
            st.execute("CREATE TABLE og_ddl (id INT PRIMARY KEY, v VARCHAR(32))");
            st.execute("INSERT INTO og_ddl VALUES (1, 'before')");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_ddl");
            st.execute("CREATE TABLE og_ddl (id INT PRIMARY KEY, v VARCHAR(32))");
        }
        Project project = newProject("og-ddl", cdcConfig("og_ddl"));
        manager.ensureStarted(project);
        waitFor("baseline row", 90_000, () -> countTarget("og_ddl") == 1);
        manager.ensureStopped(project.getId());

        // 宕机期源库结构变更:加列 + 写新行。重启后 DDL 被跳过(告警+缓存失效),
        // 新 TABLE_MAP 带新列,而目标表没有该列——产品语义是什么,实测说了算。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("ALTER TABLE og_ddl ADD COLUMN extra VARCHAR(32)");
            st.execute("INSERT INTO og_ddl VALUES (2, 'after', 'new-col')");
        }
        manager.ensureStarted(reload(project));
        // 轮询等待终态:要么行落库(某种兼容),要么项目 park ERROR(诚实报错)。
        // 固定 sleep 曾在 strike-out 重试还在进行时误判热循环——strike-out 需要多轮
        // 重试才 park ERROR,给足 90s。两种终态都接受;不可接受的是空转(超时即发现)。
        boolean[] appliedHolder = new boolean[1];
        waitFor("row applied or task parked ERROR after source DDL", 90_000, () -> {
            boolean applied = queryTarget("SELECT id FROM og_ddl WHERE id = 2").size() == 1;
            appliedHolder[0] = applied;
            return applied || "ERROR".equals(queryTaskStatus(project.getId()));
        });
        boolean row2Applied = appliedHolder[0];
        String taskStatus = queryTaskStatus(project.getId());
        log.info("after DDL+restart: row2Applied={}, taskStatus={}", row2Applied, taskStatus);
        assertThat(row2Applied || "ERROR".equals(taskStatus))
                .as("DDL 后要么行继续落库,要么项目诚实置 ERROR(当前: applied=%s status=%s)",
                        row2Applied, taskStatus)
                .isTrue();
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    private String queryTaskStatus(long projectId) {
        return taskStore.find(projectId)
                .map(t -> t.getStatus() == null ? null : t.getStatus().name())
                .orElse(null);
    }
}
