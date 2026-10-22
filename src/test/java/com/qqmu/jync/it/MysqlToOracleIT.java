package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * docker 实测矩阵 ②:MySQL 8 binlog CDC → Oracle 23ai。这就是生产上报错的那个方向
 * (MySQL 源 + Oracle 目标),此前唯独缺这一格覆盖率。
 *
 * <p>与 {@code MysqlToOpenGaussIT} 的关键差别:<b>目标表一律不预建</b>,由应用自己的
 * 结构同步建表。生产上失败的正是这一步 —— {@code CREATE TABLE "T" ("STATUS" NUMBER(3)
 * NOT NULL DEFAULT 0)} 不是 Oracle 语法,老版本报 ORA-00907,23ai 报 ORA-03076
 * "unexpected item DEFAULT in column definition"。手工预建目标表会绕过这条路径,
 * 那正是这个方向的 bug 一直没被 IT 抓住的原因。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it — run explicitly with
 * {@code mvn test -Dtest=MysqlToOracleIT}.
 */
class MysqlToOracleIT extends CdcMySqlContainerHarnessIT {

    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @BeforeEach
    void startOracle() {
        if (!ORACLE.isRunning()) {
            ORACLE.start();
        }
    }

    @Override
    protected Connection targetJdbc() throws SQLException {
        startOracle();
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }

    @Override
    protected DatabaseConfig targetConfig() {
        startOracle();
        DatabaseConfig config = new DatabaseConfig();
        config.setName("cdc-it-ora-tgt");
        config.setType(DatabaseType.ORACLE);
        config.setRole(ConnectionRole.TARGET);
        config.setHost(ORACLE.getHost());
        config.setPort(ORACLE.getMappedPort(1521));
        config.setDatabaseName(ORACLE.getDatabaseName());
        config.setSchemaName(ORACLE.getUsername().toUpperCase(java.util.Locale.ROOT));
        config.setUsername(ORACLE.getUsername());
        config.setPassword(cryptoUtil.encrypt(ORACLE.getPassword()));
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

    /** Drops an Oracle object best-effort: "did not exist" is not an error here. */
    private void dropTarget(String table) throws SQLException {
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"" + table
                    + "\" CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN NULL; END;");
        }
    }

    private void dropSource(String table) throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /** {@link #countTarget} for a wait predicate: a table that is not there yet is a 0, not a throw. */
    private long targetRowsOrMinusOne(String table) {
        try {
            return countTarget(table);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * Waits until the row shows up or the project parks ERROR, then reports which — a silent
     * timeout would hide the target's own error message (the DDL failure we are hunting).
     */
    private void awaitRowsOrError(Project project, String table, int expected, long timeoutMs) {
        waitFor("rows applied or task parked ERROR", timeoutMs,
                () -> targetRowsOrMinusOne(table) == expected
                        || "ERROR".equals(taskStatus(project.getId())));
        if (targetRowsOrMinusOne(table) != expected) {
            throw new AssertionError("expected " + expected + " row(s) in " + table
                    + " but got " + targetRowsOrMinusOne(table)
                    + "; task status=" + taskStatus(project.getId()));
        }
    }

    private String taskStatus(long projectId) {
        return taskStore.find(projectId)
                .map(t -> t.getStatus() == null ? null : t.getStatus().name())
                .orElse(null);
    }

    /**
     * 生产事故的回归:MySQL 源表的 NOT NULL + AUTO_INCREMENT + 带默认值的列,目标 Oracle
     * 表由结构同步创建。默认值必须在 NOT NULL 之前,否则 Oracle 直接拒绝建表。
     */
    @Test
    void notNullDefaultsAndAutoIncrementBuildAValidOracleTable() throws Exception {
        dropSource("ora_loc");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE ora_loc ("
                    + "LOCATION_ID BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "ZONE_ID BIGINT NULL,"
                    + "WAREHOUSE_ID BIGINT NULL,"
                    + "AISLE_ID BIGINT NULL,"
                    + "LOCATION_NO VARCHAR(100) NOT NULL,"
                    + "LOCATION_TYPE TINYINT NOT NULL DEFAULT 0,"
                    + "STATUS TINYINT NOT NULL DEFAULT 0)");
            st.execute("INSERT INTO ora_loc (ZONE_ID, WAREHOUSE_ID, AISLE_ID, LOCATION_NO,"
                    + " LOCATION_TYPE, STATUS) VALUES (1, 2, 3, 'A-01', 4, 1)");
        }
        dropTarget("ORA_LOC");

        Project project = newProject("ora-loc", cdcConfig("ora_loc"));
        manager.ensureStarted(project);
        awaitRowsOrError(project, "ORA_LOC", 1, 180_000);

        assertThat(queryTarget("SELECT LOCATION_NO, LOCATION_TYPE, STATUS FROM ORA_LOC")
                .get(0)).containsEntry("LOCATION_NO", "A-01");

        // 目标列上真的带了默认值约束,而不只是数据正确。
        assertThat(queryTarget("SELECT DATA_DEFAULT AS \"dd\" FROM ALL_TAB_COLUMNS"
                + " WHERE TABLE_NAME = 'ORA_LOC' AND COLUMN_NAME = 'STATUS'").get(0).get("dd"))
                .as("STATUS 的默认值随建表落到 Oracle")
                .isNotNull();

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /** 基线 + 增量 INSERT/UPDATE/DELETE(含主键变更)+ 停机重启不重做基线。 */
    @Test
    void baselineStreamingAndResumeAfterRestart() throws Exception {
        dropSource("ora_orders");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE ora_orders (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64) NOT NULL, amount DECIMAL(10,2) NOT NULL DEFAULT 0)");
            for (int i = 1; i <= 100; i++) {
                st.execute("INSERT INTO ora_orders VALUES (" + i + ", 'seed-" + i + "', "
                        + i + ".50)");
            }
        }
        dropTarget("ORA_ORDERS");

        Project project = newProject("ora-basic", cdcConfig("ora_orders"));
        manager.ensureStarted(project);
        waitFor("baseline done", 180_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("100 seeded rows", 120_000, () -> countTarget("ORA_ORDERS") == 100);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO ora_orders VALUES (201, 'streamed', 1.00)");
            st.execute("UPDATE ora_orders SET name = 'changed' WHERE id = 7");
            st.execute("DELETE FROM ora_orders WHERE id = 13");
            st.execute("UPDATE ora_orders SET id = 300 WHERE id = 20");
        }
        waitFor("stream applied", 120_000, () -> countTarget("ORA_ORDERS") == 100
                && queryTarget("SELECT id FROM ORA_ORDERS WHERE id = 300").size() == 1);

        // 别名显式引号小写:Oracle 把未加引号的标识符折成大写,列标签也就成了 NAME,
        // 用 "name" 去取会静默拿到 null(前面的 {NAME=changed} 就是这么骗过诊断的)。
        assertThat(queryTarget("SELECT name AS \"name\" FROM ORA_ORDERS WHERE id = 7")
                .get(0).get("name")).isEqualTo("changed");

        // 断点续传:停消费 → 目标删一行作金丝雀 + 源端再写 → 重启。重做基线会把金丝雀
        // 灌回来,续传则只回放新事件。
        manager.ensureStopped(project.getId());
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DELETE FROM ORA_ORDERS WHERE id = 5");
        }
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO ora_orders VALUES (400, 'after-stop', 2.00)");
        }
        manager.ensureStarted(reload(project));
        waitFor("post-restart row", 120_000, () -> countTarget("ORA_ORDERS") == 100
                && queryTarget("SELECT id FROM ORA_ORDERS WHERE id = 400").size() == 1
                && queryTarget("SELECT id FROM ORA_ORDERS WHERE id = 5").isEmpty());

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /** MySQL 常规类型矩阵落到 Oracle:精度、时区、二进制、长文本。 */
    @Test
    void mysqlTypeMatrixReachesOracle() throws Exception {
        dropSource("ora_types");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE ora_types (id INT PRIMARY KEY,"
                    + " c_varchar VARCHAR(64), c_dec DECIMAL(30,10), c_neg INT,"
                    + " c_big BIGINT, c_dt DATETIME(6), c_date DATE, c_double DOUBLE,"
                    + " c_txt TEXT, c_dt_text VARCHAR(32))");
            st.execute("INSERT INTO ora_types VALUES (1, '中文 emoji 🎉', 12345.6789012345,"
                    + " -42, 9223372036854775806, '2026-10-13 12:34:56.789012',"
                    + " '2026-02-28', 3.141592653589793, REPEAT('长文本', 100),"
                    + " '2026-10-13 12:34:56')");
        }
        dropTarget("ORA_TYPES");

        Project project = newProject("ora-types", cdcConfig("ora_types"));
        manager.ensureStarted(project);
        awaitRowsOrError(project, "ORA_TYPES", 1, 180_000);

        Map<String, Object> row = queryTarget("SELECT c_varchar AS \"c_varchar\","
                + " c_dec AS \"c_dec\", c_neg AS \"c_neg\", c_big AS \"c_big\","
                + " TO_CHAR(c_dt, 'YYYY-MM-DD HH24:MI:SS.FF6') AS \"dt\","
                + " TO_CHAR(c_date, 'YYYY-MM-DD') AS \"da\","
                + " c_double AS \"c_double\", c_dt_text AS \"dt_text\","
                + " DBMS_LOB.GETLENGTH(c_txt) AS \"tl\""
                + " FROM ORA_TYPES WHERE id = 1").get(0);
        assertThat(row.get("c_varchar")).isEqualTo("中文 emoji 🎉");
        // VARCHAR 列里"长得像时间戳"的文本必须原样落库。目标列是 VARCHAR2(源端类型推出来的),
        // 若绑定成 Timestamp,Oracle 会按 NLS_TIMESTAMP_FORMAT 渲染成
        // "13-10月-26 12.34.56.000000 下午" —— 一次纯文本同步被悄悄改写。
        assertThat(row.get("dt_text")).isEqualTo("2026-10-13 12:34:56");
        assertThat(((Number) row.get("c_neg")).intValue()).isEqualTo(-42);
        assertThat(((Number) row.get("c_big")).longValue()).isEqualTo(9223372036854775806L);
        assertThat(row.get("dt")).isEqualTo("2026-10-13 12:34:56.789012");
        assertThat(row.get("da")).isEqualTo("2026-02-28");
        assertThat(((Number) row.get("c_double")).doubleValue()).isEqualTo(3.141592653589793);
        assertThat(((Number) row.get("tl")).longValue())
                .as("300 个字符;若目标列是 VARCHAR2 则按字节计,CLOB 才按字符")
                .isPositive();

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /**
     * 生产事故的回归:大于 32767 字节的文本经 Oracle 的 {@code MERGE ... USING (SELECT ?)}
     * 绑定时报 ORA-01461 —— 占位符被当成 LONG,而不是它要写入的 CLOB 列。CDC 增量回放
     * 走的正是 MERGE,基线走 INSERT-first 躲过了这个问题。二进制同理。
     */
    @Test
    void largeTextAndBinaryThroughMergeDoNotRaiseOra01461() throws Exception {
        dropSource("ora_lob");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE ora_lob (id BIGINT PRIMARY KEY,"
                    + " body LONGTEXT, bin LONGBLOB)");
            st.execute("INSERT INTO ora_lob VALUES (1, REPEAT('a', 1000), REPEAT(UNHEX('41'), 10))");
        }
        dropTarget("ORA_LOB");

        Project project = newProject("ora-lob-merge", cdcConfig("ora_lob"));
        manager.ensureStarted(project);
        waitFor("baseline done", 180_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("seeded row", 120_000, () -> countTarget("ORA_LOB") == 1);

        // 120000 个汉字 = 360000 字节,远超 LONG 上限;40 KB 二进制走临时 BLOB。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("UPDATE ora_lob SET body = REPEAT('中', 120000),"
                    + " bin = REPEAT(UNHEX('42'), 40000) WHERE id = 1");
        }
        waitFor("streamed large lob", 180_000, () -> {
            try {
                Map<String, Object> row = queryTarget(
                        "SELECT DBMS_LOB.GETLENGTH(body) AS \"tl\","
                        + " DBMS_LOB.GETLENGTH(bin) AS \"bl\" FROM ORA_LOB WHERE id = 1").get(0);
                return ((Number) row.get("tl")).longValue() == 120000L
                        && ((Number) row.get("bl")).longValue() == 40000L;
            } catch (RuntimeException e) {
                return false;
            }
        });

        // JDBC 返回的是 oracle.sql.CLOB 句柄,内容用 SQL 侧函数校验。
        // DBMS_LOB.SUBSTR 的数量/起点按字符计(不是字节)。
        Map<String, Object> edges = queryTarget("SELECT DBMS_LOB.SUBSTR(body, 3, 1) AS \"head\","
                + " DBMS_LOB.SUBSTR(body, 3, 119998) AS \"tail\" FROM ORA_LOB WHERE id = 1")
                .get(0);
        assertThat(edges.get("head")).isEqualTo("中中中");
        assertThat(edges.get("tail")).isEqualTo("中中中");

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /**
     * Oracle 把 {@code ''} 绑定成 NULL,{@code NOT NULL CLOB} 目标会以 ORA-01400 拒绝。
     * 空串必须用空临时 CLOB 写入,保留"非空但长度为 0"的语义。
     */
    @Test
    void emptyStringIntoNotNullClobIsPreserved() throws Exception {
        dropSource("ora_empty");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE ora_empty (id BIGINT PRIMARY KEY,"
                    + " body LONGTEXT NOT NULL)");
            st.execute("INSERT INTO ora_empty VALUES (1, '')");
        }
        dropTarget("ORA_EMPTY");

        Project project = newProject("ora-empty-clob", cdcConfig("ora_empty"));
        manager.ensureStarted(project);
        awaitRowsOrError(project, "ORA_EMPTY", 1, 180_000);

        Map<String, Object> row = queryTarget("SELECT NVL(DBMS_LOB.GETLENGTH(body), -1)"
                + " AS \"len\" FROM ORA_EMPTY WHERE id = 1").get(0);
        assertThat(((Number) row.get("len")).longValue())
                .as("-1 表示 SQL NULL;0 表示保留下来的空值")
                .isZero();

        manager.ensureStopped(project.getId());
        disableProject(project);
    }
}
