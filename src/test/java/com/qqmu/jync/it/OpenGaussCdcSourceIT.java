package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;

/**
 * v1.9.0: openGauss 5.0.0 <b>作源库</b>的逻辑复制 CDC 真库集成测试。
 *
 * <p>与 {@link PostgresToOpenGaussIT}(PG 源 → openGauss 目标)互补:这里源与目标都在
 * openGauss 上,但分属两个 database({@code jync_source} / {@code jync_target})。逻辑槽
 * 只解码自己所属的 database,所以目标端的写入不会回流到解码流里 —— 同实例双库不会形成
 * 自激环。
 *
 * <p>这条用例覆盖的是规格 §2.2 的四处差异在真实服务器上的联合效果:prompt 的
 * {@code location} 列、{@code pg_lsn} 类型不存在、推进函数的大小写、以及最容易被漏掉的
 * <b>产品名谎言</b>——openGauss 官方驱动把 {@code getDatabaseProductName()} 答成
 * "PostgreSQL",方言与 {@code pg_current_wal_lsn()} 的替换必须靠 {@code SELECT version()}
 * 的横幅才能命中。
 */
class OpenGaussCdcSourceIT extends PgCdcHarnessIT {

    @Autowired private CdcConsumerManager manager;
    @Autowired private CdcPositionStore positionStore;
    @Autowired private CdcOperationsService cdcOperations;

    @Override
    protected GenericContainer<?> createContainer() {
        // OpenGaussSupport 已经在 container() 里启动并初始化过;Testcontainers 对重复
        // start() 是幂等的。
        return OpenGaussSupport.container();
    }

    @Override
    protected String jdbcUrl() {
        return OpenGaussSupport.sourceJdbcUrl();
    }

    @Override
    protected Connection sourceJdbc() throws SQLException {
        return OpenGaussSupport.sourceJdbc();
    }

    @Override
    protected Connection targetJdbc() throws SQLException {
        return OpenGaussSupport.jdbc();
    }

    @Override
    protected DatabaseConfig sourceConfig() {
        return savedOpenGaussConfig("cdc-og-src", ConnectionRole.SOURCE,
                OpenGaussSupport.SOURCE_DATABASE);
    }

    @Override
    protected DatabaseConfig targetConfig() {
        return savedOpenGaussConfig("cdc-og-tgt", ConnectionRole.TARGET,
                OpenGaussSupport.DATABASE);
    }

    private DatabaseConfig savedOpenGaussConfig(String name, ConnectionRole role,
                                                String database) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.OPENGAUSS);
        config.setRole(role);
        config.setHost(OpenGaussSupport.host());
        config.setPort(OpenGaussSupport.port());
        config.setDatabaseName(database);
        config.setSchemaName(SCHEMA);
        config.setUsername(OpenGaussSupport.USER);
        config.setPassword(cryptoUtil.encrypt(OpenGaussSupport.PASSWORD));
        return databaseConfigRepository.save(config);
    }

    private SyncConfig cdcConfig(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.PG_LOGICAL_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }

    private void createOrdersTable(String table, int seedRows) throws SQLException {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public." + table);
            st.execute("CREATE TABLE public." + table + " (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount NUMERIC(10,2))");
            for (int i = 1; i <= seedRows; i++) {
                st.execute("INSERT INTO public." + table + " VALUES (" + i + ", 'seed-" + i
                        + "', " + i + ".5)");
            }
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
            st.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount NUMERIC(10,2))");
        }
    }

    /** {@code 高32位/低32位} 十六进制 LSN → 可比数值;格式不符时直接失败。 */
    private static long lsnValue(String lsn) {
        String[] parts = lsn.split("/");
        if (parts.length != 2) {
            throw new AssertionError("not an LSN: " + lsn);
        }
        return (Long.parseUnsignedLong(parts[0], 16) << 32)
                | Long.parseUnsignedLong(parts[1], 16);
    }

    /**
     * 场景 0:用户真正走的那条门禁 —— 保存任务 / 测试连接时的 PG 逻辑复制探测。
     *
     * <p>前两个场景证明消费端能跑，却绕过了探测；若 ② {@code SHOW wal_level}、④ 槽配额
     * 查询或 ⑤ 建槽+删槽 在 openGauss 上有任何一项不成立，用户看到的就是"该源不可用"，
     * 而消费端 IT 全绿也发现不了。这里在真库上把五项检查整体跑一遍。
     */
    @Test
    void probePassesOnARealOpenGaussSource() throws Exception {
        createOrdersTable("og_probe_orders", 1);
        Project project = newProject("og-probe", cdcConfig("og_probe_orders"));

        CdcProbeResult result = cdcOperations.probeProject(project.getId());

        assertThat(result.getChecks()).isNotEmpty();
        assertThat(result.failures())
                .as("failed probe checks on a real openGauss source: %s", result.getChecks())
                .isEmpty();
        assertThat(result.passed()).isTrue();
        // jync 是 SYSADMIN(rolsuper/rolreplication 全为 f):角色检查必须走 openGauss 的
        // 放宽分支,而不是把真实用户拦在门外。
        assertThat(result.getChecks())
                .anyMatch(c -> CdcProbeResult.PG_REPLICATION_ROLE.equals(c.getName())
                        && c.isPassed() && c.getDetail().contains("openGauss"));

        disableProject(project);
    }

    /**
     * 方言判定的前提事实,在真库上钉住:openGauss 5.0.0 <b>没有</b> {@code pg_lsn} 类型。
     *
     * <p>这条查询就是 {@code PgLogicalDialect.detectLive} 发出的那条。不加这条用例,
     * 即使目录查询在 openGauss 上直接报错、退回 {@code SELECT version()} 横幅,消费端
     * 用例照样全绿 —— 「按能力判定」就成了纸面说法。
     */
    @Test
    void sourceCatalogHasNoPgLsnType() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_catalog.pg_type WHERE typname = 'pg_lsn'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    /**
     * 场景 1:基线 + 流式 INSERT/UPDATE/DELETE,并钉住 {@code cdc_position.lsn} 的形态。
     */
    @Test
    void baselineAndStreamedChangesFromOpenGaussSource() throws Exception {
        createOrdersTable("og_src_orders", 200);
        Project project = newProject("og-source-basic", cdcConfig("og_src_orders"));
        manager.ensureStarted(project);

        waitFor("baseline done", 120_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("200 seeded rows", 90_000, () -> countTarget("og_src_orders") == 200);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO public.og_src_orders VALUES (201, 'streamed', 1.00)");
            st.execute("UPDATE public.og_src_orders SET name = 'changed' WHERE id = 7");
            st.execute("DELETE FROM public.og_src_orders WHERE id = 13");
        }
        // count==200 在这里是空真(INSERT+DELETE 净值为零),钉住三条变更各自的语义。
        waitFor("streamed INSERT/UPDATE/DELETE applied", 90_000, () ->
                queryTarget("SELECT id FROM og_src_orders WHERE id = 201").size() == 1
                        && "changed".equals(queryTarget(
                                "SELECT name FROM og_src_orders WHERE id = 7")
                                .get(0).get("name"))
                        && queryTarget("SELECT id FROM og_src_orders WHERE id = 13").isEmpty());
        assertThat(countTarget("og_src_orders")).isEqualTo(200);
        assertThat(queryTarget("SELECT amount::text a FROM og_src_orders WHERE id = 1")
                .get(0).get("a")).isEqualTo("1.50");

        // 位点形态:openGauss 的 pg_current_xlog_location() 与推进结果的文本都是 0/…
        String lsn = positionStore.find(project.getId())
                .orElseThrow(() -> new AssertionError("no cdc_position row")).getLsn();
        assertThat(lsn).as("cdc_position.lsn on an openGauss source").isNotBlank();
        assertThat(lsn).matches("(?i)0/[0-9a-f]+");

        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    /**
     * 场景 2:停止 → 源库继续写 → 重启续跑。目标端删一行、源端插一行,两者都必须
     * 在重启后被正确反映(位点续跑而不是从头重放,也不会重复落行)。
     */
    @Test
    void restartResumesFromTheRecordedPosition() throws Exception {
        createOrdersTable("og_src_resume", 50);
        Project project = newProject("og-source-resume", cdcConfig("og_src_resume"));
        manager.ensureStarted(project);
        waitFor("baseline done", 120_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("50 seeded rows", 90_000, () -> countTarget("og_src_resume") == 50);

        manager.ensureStopped(project.getId());
        String lsnWhileStopped = positionStore.find(project.getId())
                .orElseThrow(() -> new AssertionError("no cdc_position row")).getLsn();

        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DELETE FROM og_src_resume WHERE id = 5");
        }
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO public.og_src_resume VALUES (400, 'after-stop', 2.00)");
        }

        manager.ensureStarted(reload(project));
        waitFor("post-restart row applied exactly once", 90_000, () ->
                countTarget("og_src_resume") == 50
                        && queryTarget("SELECT id FROM og_src_resume WHERE id = 400")
                                .size() == 1
                        && queryTarget("SELECT id FROM og_src_resume WHERE id = 5").isEmpty());
        // 复跑后位点必须前进过。openGauss 的推进结果是小写十六进制,与原生 PG 的大写
        // 形态并存,按数值比而不是字符串比。
        String lsnAfterResume = positionStore.find(project.getId())
                .orElseThrow(() -> new AssertionError("no cdc_position row")).getLsn();
        assertThat(lsnAfterResume).matches("(?i)0/[0-9a-f]+");
        assertThat(lsnValue(lsnAfterResume))
                .as("position advanced after the resume (%s -> %s)",
                        lsnWhileStopped, lsnAfterResume)
                .isGreaterThanOrEqualTo(lsnValue(lsnWhileStopped));

        manager.ensureStopped(project.getId());
        disableProject(project);
    }
}
