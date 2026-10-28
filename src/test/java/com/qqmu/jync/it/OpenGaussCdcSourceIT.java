package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
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
import com.qqmu.jync.service.cdc.CdcPositionStore;

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
