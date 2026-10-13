package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
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
 * docker 实测矩阵 ②:PostgreSQL 16 logical slot ({@code test_decoding}) CDC → OpenGauss
 * 5.0(信创目标)。
 *
 * <p>Same scenarios as {@link MysqlToOpenGaussIT} minus the PK-move (a default-replica-
 * identity table's UPDATE carries only the key in the old image) and the XA case (no XA in
 * PG): baseline + streamed INSERT/UPDATE/DELETE, stop/restart resume canary, a safe-type
 * matrix, PG-specific types (BOOLEAN/JSONB/UUID/BYTEA), and ADD COLUMN while stopped.
 */
class PostgresToOpenGaussIT extends PgCdcHarnessIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresToOpenGaussIT.class);

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
        config.setCaptureStrategy(CaptureStrategy.PG_LOGICAL_CDC);
        for (String t : tables) {
            config.getTables().add(t);
        }
        return config;
    }

    @Test
    void baselineStreamingAndResumeAfterRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.og_orders");
            st.execute("CREATE TABLE public.og_orders (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount DECIMAL(10,2))");
            for (int i = 1; i <= 200; i++) {
                st.execute("INSERT INTO public.og_orders VALUES (" + i + ", 'seed-" + i
                        + "', " + i + ".5)");
            }
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_orders");
            st.execute("CREATE TABLE og_orders (id BIGINT PRIMARY KEY,"
                    + " name VARCHAR(64), amount NUMERIC(10,2))");
        }
        Project project = newProject("pg-basic", cdcConfig("og_orders"));
        manager.ensureStarted(project);

        waitFor("baseline done", 90_000, () -> positionStore.find(project.getId())
                .map(p -> p.isBaselineDone()).orElse(false));
        waitFor("200 seeded rows", 60_000, () -> countTarget("og_orders") == 200);

        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO public.og_orders VALUES (201, 'streamed', 1.00)");
            st.execute("UPDATE public.og_orders SET name = 'changed' WHERE id = 7");
            st.execute("DELETE FROM public.og_orders WHERE id = 13");
        }
        // 不能用 count==200 当条件:INSERT+DELETE 净值本就是 200,基线刚完时即为真(空真)。
        // 钉语义:新行在、UPDATE 生效、DELETE 生效。
        waitFor("streamed INSERT/UPDATE/DELETE applied", 60_000, () ->
                queryTarget("SELECT id FROM og_orders WHERE id = 201").size() == 1
                        && "changed".equals(queryTarget(
                                "SELECT name FROM og_orders WHERE id = 7")
                                .get(0).get("name"))
                        && queryTarget("SELECT id FROM og_orders WHERE id = 13").isEmpty());
        assertThat(countTarget("og_orders")).isEqualTo(200);
        assertThat(queryTarget("SELECT name FROM og_orders WHERE id = 7")
                .get(0).get("name")).isEqualTo("changed");
        assertThat(queryTarget("SELECT amount::text a FROM og_orders WHERE id = 1")
                .get(0).get("a")).isEqualTo("1.50");

        // 断点续传金丝雀:停后目标删 id=5 + 源插 id=400,重启后 400 在/5 不在。
        manager.ensureStopped(project.getId());
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DELETE FROM og_orders WHERE id = 5");
        }
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("INSERT INTO public.og_orders VALUES (400, 'after-stop', 2.00)");
        }
        manager.ensureStarted(reload(project));
        waitFor("post-restart row", 60_000, () -> countTarget("og_orders") == 200
                && queryTarget("SELECT id FROM og_orders WHERE id = 400").size() == 1
                && queryTarget("SELECT id FROM og_orders WHERE id = 5").isEmpty());
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void safeTypeMatrixReachesOpenGauss() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.og_types");
            st.execute("CREATE TABLE public.og_types (id INT PRIMARY KEY,"
                    + " c_varchar VARCHAR(64), c_dec NUMERIC(30,10), c_neg INT,"
                    + " c_big BIGINT, c_ts TIMESTAMP(6), c_date DATE, c_time TIME,"
                    + " c_double DOUBLE PRECISION, c_txt TEXT, c_bin BYTEA)");
            st.execute("INSERT INTO public.og_types VALUES (1,"
                    + " '中文 emoji 🎉', 12345.6789012345, -42, 9223372036854775806,"
                    + " '2026-10-13 12:34:56.789012', '2026-02-28', '12:34:56',"
                    + " 3.141592653589793, repeat('长文本', 100), decode('DEADBEEF','hex'))");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_types");
            st.execute("CREATE TABLE og_types (id INT PRIMARY KEY,"
                    + " c_varchar VARCHAR(64), c_dec NUMERIC(30,10), c_neg INT,"
                    + " c_big BIGINT, c_ts TIMESTAMP(6), c_date DATE, c_time TIME,"
                    + " c_double DOUBLE PRECISION, c_txt TEXT, c_bin BYTEA)");
        }
        Project project = newProject("pg-types", cdcConfig("og_types"));
        manager.ensureStarted(project);
        waitFor("type row applied", 90_000, () -> countTarget("og_types") == 1);

        Map<String, Object> row = queryTarget("SELECT c_varchar, c_dec::text d, c_neg,"
                + " c_big, to_char(c_ts, 'YYYY-MM-DD HH24:MI:SS.US') ts,"
                + " c_date::text da, c_time::text t, c_double, octet_length(c_txt) tl,"
                + " encode(c_bin, 'hex') bh FROM og_types WHERE id = 1").get(0);
        assertThat(row.get("c_varchar")).isEqualTo("中文 emoji 🎉");
        assertThat((String) row.get("d")).startsWith("12345.6789012345");
        assertThat(row.get("c_neg")).isEqualTo(-42);
        assertThat(row.get("c_big")).isEqualTo(9223372036854775806L);
        assertThat(row.get("ts")).isEqualTo("2026-10-13 12:34:56.789012");
        assertThat((String) row.get("da")).startsWith("2026-02-28");
        assertThat((String) row.get("t")).startsWith("12:34:56");
        assertThat((Double) row.get("c_double")).isEqualTo(3.141592653589793);
        assertThat(row.get("tl")).isEqualTo(900);
        assertThat(row.get("bh")).isEqualTo("deadbeef");
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void pgSpecificTypesReachOpenGauss() throws Exception {
        // PG 特别字段:BOOLEAN、JSONB、UUID、BYTEA——「特别字段没有识别」的怀疑对象。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.og_pgtypes");
            st.execute("CREATE TABLE public.og_pgtypes (id INT PRIMARY KEY,"
                    + " c_bool BOOLEAN, c_jsonb JSONB, c_uuid UUID, c_bin BYTEA)");
            st.execute("INSERT INTO public.og_pgtypes VALUES (1,"
                    + " TRUE, '{\"k\": 1, \"s\": \"中文\"}'::jsonb,"
                    + " '550e8400-e29b-41d4-a716-446655440000',"
                    + " decode('CAFE','hex'))");
            // 第二行全 NULL:钉 setNull 的类型路径(OTHER 列若按 1111 发 null,部分驱动
            // 会拒),NULL 必须和非 NULL 行一样落库。
            st.execute("INSERT INTO public.og_pgtypes VALUES (2,"
                    + " NULL, NULL, NULL, NULL)");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            // JSONB/UUID 走文本承载(openGauss SQL_ASCII 目标,字符串往返最稳)。
            st.execute("DROP TABLE IF EXISTS og_pgtypes");
            st.execute("CREATE TABLE og_pgtypes (id INT PRIMARY KEY,"
                    + " c_bool BOOLEAN, c_jsonb TEXT, c_uuid VARCHAR(36), c_bin BYTEA)");
        }
        Project project = newProject("pg-pgtypes", cdcConfig("og_pgtypes"));
        manager.ensureStarted(project);
        waitFor("pg-specific rows applied", 90_000,
                () -> countTarget("og_pgtypes") == 2);

        Map<String, Object> row = queryTarget("SELECT c_bool, c_jsonb, c_uuid,"
                + " encode(c_bin, 'hex') bh FROM og_pgtypes WHERE id = 1").get(0);
        assertThat(row.get("c_bool")).isEqualTo(true);
        assertThat((String) row.get("c_jsonb")).contains("\"k\": 1").contains("中文");
        assertThat(row.get("c_uuid")).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(row.get("bh")).isEqualTo("cafe");

        Map<String, Object> nullRow = queryTarget("SELECT c_bool, c_jsonb, c_uuid,"
                + " encode(c_bin, 'hex') bh FROM og_pgtypes WHERE id = 2").get(0);
        assertThat(nullRow.get("c_bool")).isNull();
        assertThat(nullRow.get("c_jsonb")).isNull();
        assertThat(nullRow.get("c_uuid")).isNull();
        assertThat(nullRow.get("bh")).isNull();
        manager.ensureStopped(project.getId());
        disableProject(project);
    }

    @Test
    void ddlAddColumnWhileStoppedThenRestart() throws Exception {
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("DROP TABLE IF EXISTS public.og_ddl");
            st.execute("CREATE TABLE public.og_ddl (id INT PRIMARY KEY, v VARCHAR(32))");
            st.execute("INSERT INTO public.og_ddl VALUES (1, 'before')");
        }
        try (Connection tgt = targetJdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS og_ddl");
            st.execute("CREATE TABLE og_ddl (id INT PRIMARY KEY, v VARCHAR(32))");
        }
        Project project = newProject("pg-ddl", cdcConfig("og_ddl"));
        manager.ensureStarted(project);
        waitFor("baseline row", 90_000, () -> countTarget("og_ddl") == 1);
        manager.ensureStopped(project.getId());

        // 宕机期源库结构变更:加列 + 写新行。
        try (Connection src = sourceJdbc(); Statement st = src.createStatement()) {
            st.execute("ALTER TABLE public.og_ddl ADD COLUMN extra VARCHAR(32)");
            st.execute("INSERT INTO public.og_ddl VALUES (2, 'after', 'new-col')");
        }
        manager.ensureStarted(reload(project));
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
}
