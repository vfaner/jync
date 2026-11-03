package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: KingbaseES as both source and target, polling strategy.
 *
 * <p>Kingbase publishes no container image, so this test is opt-in against a live instance.
 * Both endpoints share one server and one login; two schemas keep them apart the same way the
 * product would be deployed. The driver is bundled, so the URL is the only requirement:
 *
 * <pre>
 * JYNC_KINGBASE_IT_URL=jdbc:kingbase8://host:54321/test \
 * JYNC_KINGBASE_IT_USER=system JYNC_KINGBASE_IT_PASSWORD=... \
 * mvn -q test -Dtest=KingbasePollingIT
 * </pre>
 *
 * <p>Optional: {@code JYNC_KINGBASE_IT_SOURCE_SCHEMA} (default {@code jync_it_src}) and
 * {@code JYNC_KINGBASE_IT_TARGET_SCHEMA} (default {@code jync_it_tgt}). The schemas are
 * created and dropped by the test.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "JYNC_KINGBASE_IT_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:kingbaseit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
class KingbasePollingIT {

    private static final String TABLE = "KB_ORDERS";
    private static final String SOURCE_SCHEMA =
            envOrDefault("JYNC_KINGBASE_IT_SOURCE_SCHEMA", "jync_it_src");
    private static final String TARGET_SCHEMA =
            envOrDefault("JYNC_KINGBASE_IT_TARGET_SCHEMA", "jync_it_tgt");

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aPollingCycleCreatesTheTargetAndCopiesRows() throws Exception {
        String url = System.getenv("JYNC_KINGBASE_IT_URL");
        String user = System.getenv("JYNC_KINGBASE_IT_USER");
        String password = System.getenv("JYNC_KINGBASE_IT_PASSWORD");
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SOURCE_SCHEMA);
            st.execute("CREATE SCHEMA IF NOT EXISTS " + TARGET_SCHEMA);
            st.execute("DROP TABLE IF EXISTS " + SOURCE_SCHEMA + "." + TABLE);
            st.execute("DROP TABLE IF EXISTS " + TARGET_SCHEMA + "." + TABLE);
            st.execute("CREATE TABLE " + SOURCE_SCHEMA + "." + TABLE + " ("
                    + "ID BIGINT PRIMARY KEY, NAME VARCHAR(50) NOT NULL,"
                    + " AMOUNT DECIMAL(10,2),"
                    + " UPDATED_AT TIMESTAMP NOT NULL)");
            st.execute("INSERT INTO " + SOURCE_SCHEMA + "." + TABLE + " VALUES"
                    + " (1,'ann',10.50,'2026-01-01 00:00:00'),"
                    + " (2,'bob',20.00,'2026-01-01 00:00:01')");
        }
        Project project = savedProject(url, user, password);

        try {
            SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

            assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
            assertThat(first.getRowsInserted()).isEqualTo(2);
            assertThat(targetNames(url, user, password))
                    .containsEntry("1", "ann").containsEntry("2", "bob");

            // A second cycle must not duplicate the rows the first one delivered.
            SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));
            assertThat(second.isSuccess()).as("空转错误: %s", second.getErrors()).isTrue();
            assertThat(targetCount(url, user, password)).isEqualTo(2);
        } finally {
            dropSchemas(url, user, password);
        }
    }

    private Project savedProject(String url, String user, String password) {
        DatabaseConfig source = savedConfig("kb-it-src", url, SOURCE_SCHEMA, user, password,
                ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("kb-it-tgt", url, TARGET_SCHEMA, user, password,
                ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        config.setCursorColumns(Map.of(TABLE, "UPDATED_AT"));
        Project project = new Project();
        project.setName("kingbase-polling-it");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private DatabaseConfig savedConfig(String name, String url, String schema, String user,
            String password, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.KINGBASE);
        config.setRole(role);
        config.setCustomUrl(url);
        config.setSchemaName(schema);
        config.setUsername(user);
        config.setPassword(cryptoUtil.encrypt(password));
        return databaseConfigRepository.save(config);
    }

    private Map<String, String> targetNames(String url, String user, String password)
            throws Exception {
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ID, NAME FROM " + TARGET_SCHEMA + "."
                     + TABLE + " ORDER BY ID")) {
            while (rs.next()) {
                rows.put(String.valueOf(rs.getLong("ID")), rs.getString("NAME"));
            }
        }
        return rows;
    }

    private long targetCount(String url, String user, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TARGET_SCHEMA + "."
                     + TABLE)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void dropSchemas(String url, String user, String password) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TARGET_SCHEMA + "." + TABLE);
            st.execute("DROP TABLE IF EXISTS " + SOURCE_SCHEMA + "." + TABLE);
            st.execute("DROP SCHEMA IF EXISTS " + TARGET_SCHEMA);
            st.execute("DROP SCHEMA IF EXISTS " + SOURCE_SCHEMA);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
