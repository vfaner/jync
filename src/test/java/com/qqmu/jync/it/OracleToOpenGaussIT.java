package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
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
 * docker 实测矩阵 ④:Oracle 23ai Free (gvenzl/oracle-free:23, CDB) LogMiner CDC →
 * openGauss 5.0(信创目标)。
 *
 * <p>源连接落在 PDB(SRC 用户),配置挖掘账号 C##MINER —— 消费者 resolveMining:
 * PDB + miner → ROOT,开 CDB$ROOT 连接挖掘,V$LOGMNR_CONTENTS.SRC_CON_ID 过滤回本 PDB。
 * Oracle 标识符全大写,方言给标识符加双引号,所以 openGauss 目标表一律<b>带引号
 * 大写</b>手工预建("OG_ORDERS"/"OG_TYPES"/"OG_DDL")。
 *
 * <p>场景:基线全量 + 流式 INSERT/UPDATE/DELETE、断点续传 kill 重启金丝雀、
 * Oracle 类型矩阵(VARCHAR2/CHAR/NUMBER 族/TIMESTAMP/DATE/RAW)、停机期源库
 * ADD COLUMN 后重启(诚实 park ERROR 或继续落库)。
 */
class OracleToOpenGaussIT extends OracleCdcHarnessIT {

    private static final Logger log = LoggerFactory.getLogger(OracleToOpenGaussIT.class);

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;

    @Override
    protected Connection targetJdbc() throws SQLException {
        return OpenGaussSupport.jdbc();
    }

    @Override
    protected DatabaseConfig sourceConfig() {
        DatabaseConfig config = super.sourceConfig();
        // CDB 必须:mining session 在 CDB$ROOT,以 C## 公共账号执行(measured ORA-65040)。
        config.setMinerUsername(MINER_USER);
        config.setMinerPassword(cryptoUtil.encrypt(MINER_PASSWORD));
        return databaseConfigRepository.save(config);
    }

    @Override
    protected DatabaseConfig targetConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("oracdc-it-og-tgt");
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

    private SyncConfig logMinerConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.LOGMINER_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }

    private void recreateTargetTable(String ddl) throws SQLException {
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute(ddl);
        }
    }

    private List<Map<String, Object>> targetQuery(String sql) {
        return queryTarget(sql);
    }

    @Test
    void baselineStreamingAndResumeAfterRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE og_orders (id NUMBER(19) PRIMARY KEY,"
                    + " name VARCHAR2(64), amount NUMBER(12,2))");
            st.execute("ALTER TABLE og_orders ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            for (int i = 1; i <= 100; i++) {
                st.execute("INSERT INTO og_orders VALUES (" + i + ", 'seed-" + i
                        + "', " + i + ".5)");
            }
        }
        recreateTargetTable("DROP TABLE IF EXISTS \"OG_ORDERS\" CASCADE");
        recreateTargetTable("CREATE TABLE \"OG_ORDERS\" (\"ID\" BIGINT PRIMARY KEY,"
                + " \"NAME\" VARCHAR(64), \"AMOUNT\" NUMERIC(12,2))");

        Project project = newProject("oracle-og-basic", logMinerConfig("OG_ORDERS"));
        manager.ensureStarted(project);

        waitFor("baseline done", 180_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone() && p.getScn() != null && p.getScn() > 0)
                .orElse(false));
        waitFor("100 seeded rows", 90_000,
                () -> countTarget("\"OG_ORDERS\"") == 100);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO og_orders VALUES (101, 'streamed', 1.00)");
            st.execute("UPDATE og_orders SET name = 'changed' WHERE id = 7");
            st.execute("DELETE FROM og_orders WHERE id = 13");
        }
        waitFor("streamed INSERT/UPDATE/DELETE applied", 90_000, () ->
                targetQuery("SELECT \"ID\" FROM \"OG_ORDERS\" WHERE \"ID\" = 101").size() == 1
                        && "changed".equals(targetQuery(
                                "SELECT \"NAME\" FROM \"OG_ORDERS\" WHERE \"ID\" = 7")
                                .get(0).get("NAME"))
                        && targetQuery("SELECT \"ID\" FROM \"OG_ORDERS\" WHERE \"ID\" = 13")
                                .isEmpty());
        assertThat(countTarget("\"OG_ORDERS\"")).isEqualTo(100);
        assertThat(targetQuery("SELECT \"AMOUNT\"::text a FROM \"OG_ORDERS\""
                + " WHERE \"ID\" = 1").get(0).get("a")).isEqualTo("1.50");

        // 断点续传金丝雀:停后目标删 id=5 + 源插 id=200,重启后 200 在/5 不回。
        manager.ensureStopped(project.getId());
        recreateTargetTable("DELETE FROM \"OG_ORDERS\" WHERE \"ID\" = 5");
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO og_orders VALUES (200, 'after-stop', 2.00)");
        }
        manager.ensureStarted(reload(project));
        waitFor("post-restart row applied without replaying deleted id=5", 90_000, () ->
                targetQuery("SELECT \"ID\" FROM \"OG_ORDERS\" WHERE \"ID\" = 200").size() == 1
                        && targetQuery("SELECT \"ID\" FROM \"OG_ORDERS\" WHERE \"ID\" = 5")
                                .isEmpty()
                        && countTarget("\"OG_ORDERS\"") == 100);

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void oracleTypeMatrixReachesOpenGauss() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE og_types (id NUMBER(19) PRIMARY KEY,"
                    + " c_vc VARCHAR2(64), c_char CHAR(16), c_num NUMBER(12,2),"
                    + " c_int NUMBER(7), c_ts TIMESTAMP(6), c_date DATE, c_raw RAW(8))");
            st.execute("ALTER TABLE og_types ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            st.execute("INSERT INTO og_types VALUES (1, '中文 emoji 🎉', 'fixed',"
                    + " 12345.67, -42,"
                    + " TO_TIMESTAMP('2026-10-13 12:34:56.789012',"
                    + "'YYYY-MM-DD HH24:MI:SS.FF6'),"
                    + " TO_DATE('2026-02-28 09:10:11','YYYY-MM-DD HH24:MI:SS'),"
                    + " HEXTORAW('DEADBEEF'))");
        }
        recreateTargetTable("DROP TABLE IF EXISTS \"OG_TYPES\" CASCADE");
        recreateTargetTable("CREATE TABLE \"OG_TYPES\" (\"ID\" BIGINT PRIMARY KEY,"
                + " \"C_VC\" VARCHAR(64), \"C_CHAR\" CHAR(16), \"C_NUM\" NUMERIC(12,2),"
                + " \"C_INT\" INT, \"C_TS\" TIMESTAMP(6), \"C_DATE\" TIMESTAMP(0),"
                + " \"C_RAW\" BYTEA)");

        Project project = newProject("oracle-og-types", logMinerConfig("OG_TYPES"));
        manager.ensureStarted(project);
        waitFor("type row applied", 180_000,
                () -> countTarget("\"OG_TYPES\"") == 1);

        Map<String, Object> row = targetQuery("SELECT \"C_VC\" AS c_vc,"
                + " \"C_NUM\"::text AS c_num, \"C_INT\" AS c_int,"
                + " to_char(\"C_TS\", 'YYYY-MM-DD HH24:MI:SS.US') AS c_ts,"
                + " to_char(\"C_DATE\", 'YYYY-MM-DD HH24:MI:SS') AS c_date,"
                + " encode(\"C_RAW\", 'hex') AS c_raw"
                + " FROM \"OG_TYPES\" WHERE \"ID\" = 1").get(0);
        log.info("Oracle type matrix landed: {}", row);
        assertThat(row.get("c_vc")).isEqualTo("中文 emoji 🎉");
        assertThat((String) row.get("c_num")).startsWith("12345.67");
        assertThat(row.get("c_int")).isEqualTo(-42);
        assertThat((String) row.get("c_ts")).startsWith("2026-10-13 12:34:56.789");
        assertThat((String) row.get("c_date")).startsWith("2026-02-28 09:10:11");
        assertThat(row.get("c_raw")).isEqualTo("deadbeef");

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /**
     * docker 实测:Oracle 的 TIMESTAMP/DATE <b>增量</b>(LogMiner)落到非 Oracle 目标。
     *
     * <p>上面那格类型矩阵测的是<b>基线</b>——基线走 JDBC,拿到的是 java.sql.Timestamp,
     * 目标驱动直接吃。增量走的是 SQL_REDO <b>文本</b>,而 LogMiner 是按<b>挖掘会话的
     * NLS</b> 渲染字面量的:容器默认中文 NLS 渲染成
     * {@code TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')}
     * (OracleLiteralConvertersTest 里那条实机 fixture 就是它)。OracleLiteralConverters
     * 把这串原样透出给目标驱动,openGauss/MySQL 都解析不了 —— 整条流断在这个列上。
     * 该行为与目标无关:只要目标不是同一个 NLS 的 Oracle,就解析不了。
     */
    @Test
    void streamedTimestampReachesANonOracleTarget() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE og_temporal (id NUMBER(19) PRIMARY KEY,"
                    + " c_ts TIMESTAMP(6), c_date DATE)");
            st.execute("ALTER TABLE og_temporal ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            st.execute("INSERT INTO og_temporal VALUES (1,"
                    + " TO_TIMESTAMP('2026-01-02 03:04:05.000001','YYYY-MM-DD HH24:MI:SS.FF6'),"
                    + " TO_DATE('2026-01-02 03:04:05','YYYY-MM-DD HH24:MI:SS'))");
        }
        recreateTargetTable("DROP TABLE IF EXISTS \"OG_TEMPORAL\" CASCADE");
        recreateTargetTable("CREATE TABLE \"OG_TEMPORAL\" (\"ID\" BIGINT PRIMARY KEY,"
                + " \"C_TS\" TIMESTAMP(6), \"C_DATE\" TIMESTAMP(0))");

        Project project = newProject("oracle-og-temporal", logMinerConfig("OG_TEMPORAL"));
        manager.ensureStarted(project);
        waitFor("temporal baseline row", 180_000, () -> countTarget("\"OG_TEMPORAL\"") == 1);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("UPDATE og_temporal SET"
                    + " c_ts = TO_TIMESTAMP('2027-03-04 05:06:07.123456',"
                    + " 'YYYY-MM-DD HH24:MI:SS.FF6'),"
                    + " c_date = TO_DATE('2027-03-04 05:06:07','YYYY-MM-DD HH24:MI:SS')"
                    + " WHERE id = 1");
        }
        waitFor("streamed temporal update applied or task parked ERROR", 120_000, () ->
                "2027-03-04 05:06:07.123456".equals(targetTemporal("C_TS"))
                        || "ERROR".equals(queryTaskStatus(project.getId())));

        assertThat(targetTemporal("C_TS"))
                .as("LogMiner 增量里的 TIMESTAMP 必须是非 Oracle 目标也能解析的字面量"
                        + "(任务状态 %s)", queryTaskStatus(project.getId()))
                .isEqualTo("2027-03-04 05:06:07.123456");
        assertThat(targetTemporal("C_DATE"))
                .as("LogMiner 增量里的 DATE 同上")
                .isEqualTo("2027-03-04 05:06:07.000000");

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /** One column of the single test row, rendered server-side to dodge driver formatting. */
    private String targetTemporal(String column) {
        List<Map<String, Object>> rows = targetQuery("SELECT to_char(\"" + column + "\","
                + " 'YYYY-MM-DD HH24:MI:SS.US') AS v FROM \"OG_TEMPORAL\" WHERE \"ID\" = 1");
        return rows.isEmpty() ? null : (String) rows.get(0).get("v");
    }

    /**
     * docker 实测:外联 LOB 的增量。**LogMiner 根本看不到这个值** —— Oracle 的补全日志
     * 不记录 LOB 列,3000+ 字符的 CLOB 存在 LOB 段里,所以 SQL_REDO 的 SET 里没有它、
     * WHERE 的旧镜像里也没有它(实机样本):
     * <pre>
     * update "SRC"."OG_LOB" set "BLOB_IN" = HEXTORAW('deadbeef')
     *   where "ID" = '1' and ROWID = 'AAAR4TAAYAAAAALAAA';
     * </pre>
     * 以前这一列会被静默写成 NULL(绑定按全列来、缺的键当 null),没有告警、没有计数、
     * 任务状态还是 RUNNING —— 目标上的数据被无声地毁掉。现在整条变更走"跳过 + 计数":
     * 目标保留变更前的版本,运营能看到 skippedUnsupported 在涨、WARN 里点名是哪一列。
     *
     * <p>这同时是<b>能力边界</b>:外联 LOB 的增量靠 LogMiner 无法复现,想要 LOB 也同步
     * 只能回源按 ROWID/主键取值(尚未实现)。内联的小 LOB 不受影响。
     */
    @Test
    void streamedOutOfLineLobIsSkippedInsteadOfNullingTheTarget() throws Exception {
        String small = "clob-seed";
        // 3600 字符 ≈ 10800 字节,超过 Oracle 的 LOB 内联阈值,强制走真实 LOB 段。
        String large = "长文本".repeat(1200);
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE og_lob (id NUMBER(19) PRIMARY KEY,"
                    + " clob_in CLOB, blob_in BLOB)");
            st.execute("ALTER TABLE og_lob ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            st.execute("INSERT INTO og_lob VALUES (1, '" + small + "', HEXTORAW('AABB'))");
        }
        recreateTargetTable("DROP TABLE IF EXISTS \"OG_LOB\" CASCADE");
        recreateTargetTable("CREATE TABLE \"OG_LOB\" (\"ID\" BIGINT PRIMARY KEY,"
                + " \"CLOB_IN\" TEXT, \"BLOB_IN\" BYTEA)");

        Project project = newProject("oracle-og-lob", logMinerConfig("OG_LOB"));
        manager.ensureStarted(project);
        waitFor("lob baseline row", 180_000, () -> countTarget("\"OG_LOB\"") == 1);
        long skippedBefore = skippedUnsupported(project.getId());

        // 值必须绑定进来:Oracle 的 SQL 字面量上限是 4000 字节,内联这条 3600 字符的
        // 中文串直接 ORA-01704(字符串文字太长)。绑定走的是真正的 CLOB 段。
        try (Connection src = sourceJdbc();
             java.sql.PreparedStatement ps = src.prepareStatement(
                     "UPDATE og_lob SET clob_in = ?, blob_in = HEXTORAW('DEADBEEF')"
                             + " WHERE id = 1")) {
            ps.setClob(1, new java.io.StringReader(large));
            ps.executeUpdate();
        }

        waitFor("out-of-line LOB change skipped or task parked ERROR", 120_000, () ->
                skippedUnsupported(project.getId()) > skippedBefore
                        || "ERROR".equals(queryTaskStatus(project.getId())));

        // 关键断言:目标上的现值一个字节都不能被写坏。
        assertThat(skippedUnsupported(project.getId()))
                .as("外联 LOB 的变更必须被计数跳过(任务状态 %s)", queryTaskStatus(project.getId()))
                .isGreaterThan(skippedBefore);
        assertThat(targetClobLength())
                .as("目标 CLOB 保留变更前的值,而不是被写成 NULL(任务状态 %s)",
                        queryTaskStatus(project.getId()))
                .isEqualTo(small.length());
        assertThat(targetBlobHex())
                .as("整条变更一起跳过,行保持变更前的完整版本")
                .isEqualTo("aabb");

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /** Changes the pipeline refused to apply because LogMiner could not reproduce them. */
    private long skippedUnsupported(long projectId) {
        com.qqmu.jync.service.cdc.CdcRuntimeStats stats = manager.statsFor(projectId);
        if (stats == null) {
            return 0L;
        }
        Object value = stats.snapshot().get("skippedUnsupported");
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** Character count of the streamed CLOB, or null while the row is missing/unchanged. */
    private Integer targetClobLength() {
        List<Map<String, Object>> rows = targetQuery(
                "SELECT length(\"CLOB_IN\") AS v FROM \"OG_LOB\" WHERE \"ID\" = 1");
        return rows.isEmpty() || rows.get(0).get("v") == null
                ? null : ((Number) rows.get(0).get("v")).intValue();
    }

    private String targetBlobHex() {
        List<Map<String, Object>> rows = targetQuery(
                "SELECT encode(\"BLOB_IN\", 'hex') AS v FROM \"OG_LOB\" WHERE \"ID\" = 1");
        return rows.isEmpty() ? null : (String) rows.get(0).get("v");
    }

    @Test
    void ddlAddColumnWhileStoppedThenRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("CREATE TABLE og_ddl (id NUMBER(19) PRIMARY KEY, v VARCHAR2(32))");
            st.execute("ALTER TABLE og_ddl ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            st.execute("INSERT INTO og_ddl VALUES (1, 'before')");
        }
        recreateTargetTable("DROP TABLE IF EXISTS \"OG_DDL\" CASCADE");
        recreateTargetTable("CREATE TABLE \"OG_DDL\" (\"ID\" BIGINT PRIMARY KEY,"
                + " \"V\" VARCHAR(32))");

        Project project = newProject("oracle-og-ddl", logMinerConfig("OG_DDL"));
        manager.ensureStarted(project);
        waitFor("baseline row", 180_000,
                () -> countTarget("\"OG_DDL\"") == 1);
        manager.ensureStopped(project.getId());

        // 宕机期源库结构变更:加列 + 写新行。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("ALTER TABLE og_ddl ADD (extra VARCHAR2(32))");
            st.execute("INSERT INTO og_ddl VALUES (2, 'after', 'new-col')");
        }
        manager.ensureStarted(reload(project));
        final boolean[] appliedHolder = new boolean[1];
        waitFor("row applied or task parked ERROR after source DDL", 120_000, () -> {
            boolean applied = targetQuery("SELECT \"ID\" FROM \"OG_DDL\" WHERE \"ID\" = 2")
                    .size() == 1;
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
