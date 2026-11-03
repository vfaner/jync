package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
 * Real-database matrix: YashanDB polling, source schema to target schema.
 *
 * <p>Yashan has no public container image, so this test is opt-in against a live instance.
 * A schema in Yashan is an account, so the target side needs its own schema name (and, where
 * the operator provisions a second account, its own credentials):
 *
 * <pre>
 * JYNC_YASHAN_IT_URL=jdbc:yasdb://host:1688/yashan \
 * JYNC_YASHAN_IT_USER=...  JYNC_YASHAN_IT_PASSWORD=... \
 * JYNC_YASHAN_IT_SCHEMA=JYNC_SRC  JYNC_YASHAN_IT_TARGET_SCHEMA=JYNC_TGT \
 * [JYNC_YASHAN_IT_TARGET_USER=... JYNC_YASHAN_IT_TARGET_PASSWORD=...] \
 * mvn -q test -Dtest=YashanDbPollingIT
 * </pre>
 *
 * <p>Both schemas must already exist and be writable by the login; this test creates and
 * drops only its own table. It asserts polling sync, not YStream CDC — the log-based path
 * needs its own probe and is not claimed here.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "JYNC_YASHAN_IT_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yashanit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
class YashanDbPollingIT {

    private static final String TABLE = "YS_ORDERS";

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aPollingCycleCreatesTheTargetAndCopiesRows() throws Exception {
        String url = System.getenv("JYNC_YASHAN_IT_URL");
        String user = System.getenv("JYNC_YASHAN_IT_USER");
        String password = System.getenv("JYNC_YASHAN_IT_PASSWORD");
        String sourceSchema = System.getenv("JYNC_YASHAN_IT_SCHEMA");
        String targetSchema = System.getenv("JYNC_YASHAN_IT_TARGET_SCHEMA");
        assumeTrue(sourceSchema != null && !sourceSchema.isBlank()
                        && targetSchema != null && !targetSchema.isBlank(),
                "JYNC_YASHAN_IT_SCHEMA and JYNC_YASHAN_IT_TARGET_SCHEMA are required");
        String targetUrl = envOrDefault("JYNC_YASHAN_IT_TARGET_URL", url);
        String targetUser = envOrDefault("JYNC_YASHAN_IT_TARGET_USER", user);
        String targetPassword = envOrDefault("JYNC_YASHAN_IT_TARGET_PASSWORD", password);

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            dropQuietly(st, targetSchema, TABLE);
            dropQuietly(st, sourceSchema, TABLE);
            st.execute("CREATE TABLE " + sourceSchema + "." + TABLE + " ("
                    + "ID NUMBER PRIMARY KEY, NAME VARCHAR2(50) NOT NULL,"
                    + " AMOUNT NUMBER(10,2),"
                    + " UPDATED_AT TIMESTAMP NOT NULL)");
            st.execute("INSERT INTO " + sourceSchema + "." + TABLE + " VALUES"
                    + " (1,'ann',10.50,TIMESTAMP '2026-01-01 00:00:00')");
            st.execute("INSERT INTO " + sourceSchema + "." + TABLE + " VALUES"
                    + " (2,'bob',20.00,TIMESTAMP '2026-01-01 00:00:01')");
        }
        Project project = savedProject(url, sourceSchema, user, password, targetUrl,
                targetSchema, targetUser, targetPassword);

        try {
            SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

            assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
            assertThat(first.getRowsInserted()).isEqualTo(2);
            assertThat(targetNames(targetUrl, targetUser, targetPassword, targetSchema))
                    .containsEntry("1", "ann").containsEntry("2", "bob");
        } finally {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement st = conn.createStatement()) {
                dropQuietly(st, targetSchema, TABLE);
                dropQuietly(st, sourceSchema, TABLE);
            }
        }
    }

    private Project savedProject(String sourceUrl, String sourceSchema, String sourceUser,
            String sourcePassword, String targetUrl, String targetSchema, String targetUser,
            String targetPassword) {
        DatabaseConfig source = savedConfig("ys-it-src", sourceUrl, sourceSchema, sourceUser,
                sourcePassword, ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("ys-it-tgt", targetUrl, targetSchema, targetUser,
                targetPassword, ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        config.setCursorColumns(Map.of(TABLE, "UPDATED_AT"));
        Project project = new Project();
        project.setName("yashan-polling-it");
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
        config.setType(DatabaseType.YASHANDB);
        config.setRole(role);
        config.setCustomUrl(url);
        config.setSchemaName(schema);
        config.setUsername(user);
        config.setPassword(cryptoUtil.encrypt(password));
        return databaseConfigRepository.save(config);
    }

    private Map<String, String> targetNames(String url, String user, String password,
            String schema) throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ID, NAME FROM " + schema + "."
                     + TABLE + " ORDER BY ID")) {
            while (rs.next()) {
                rows.put(String.valueOf(rs.getLong("ID")), rs.getString("NAME"));
            }
        }
        return rows;
    }

    private static void dropQuietly(Statement st, String schema, String table) {
        try {
            st.execute("DROP TABLE " + schema + "." + table);
        } catch (Exception ignored) {
            // First run: the table does not exist yet.
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
