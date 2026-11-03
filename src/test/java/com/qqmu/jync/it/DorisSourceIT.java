package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: Apache Doris as a polling source into PostgreSQL 16.
 *
 * <p>Doris publishes a heavy image and its deployment is architecture-sensitive, so this
 * test is opt-in against a live frontend. The target is a stock PostgreSQL container; where
 * Docker is unavailable the class skips like the other container ITs.
 *
 * <pre>
 * JYNC_DORIS_IT_URL=jdbc:mysql://host:9030/jync_it \
 * JYNC_DORIS_IT_USER=root JYNC_DORIS_IT_PASSWORD=... \
 * mvn -q test -Dtest=DorisSourceIT -Dapi.version=1.44
 * </pre>
 *
 * <p>The database named by the connection URL must exist; the test creates and drops only
 * its own duplicate-key table. Doris is read keyless (its sort key is not a unique key), so
 * the load exercises the keyless OFFSET walk.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "JYNC_DORIS_IT_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dorisit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false"
})
class DorisSourceIT {

    private static final String TABLE = "jync_it_orders";
    private static final String USER = "test";
    private static final String PASSWORD = "test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("tgt")
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private DataSourceManager dataSourceManager;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aKeylessDorisTableLoadsIntoPostgres() throws Exception {
        String url = System.getenv("JYNC_DORIS_IT_URL");
        String user = System.getenv("JYNC_DORIS_IT_USER");
        String password = System.getenv("JYNC_DORIS_IT_PASSWORD");
        String database;
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            database = currentDatabase(conn);
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " ("
                    + " `id` BIGINT,"
                    + " `name` VARCHAR(50),"
                    + " `amount` DECIMAL(10,2),"
                    + " `updated_at` DATETIME"
                    + ") DUPLICATE KEY(`id`)"
                    + " DISTRIBUTED BY HASH(`id`) BUCKETS 1"
                    + " PROPERTIES(\"replication_num\" = \"1\")");
            st.execute("INSERT INTO " + TABLE + " VALUES"
                    + " (1,'ann',10.50,'2026-01-01 00:00:00'),"
                    + " (2,'bob',20.00,'2026-01-01 00:00:01')");
        }
        Project project = savedProject(url, database, user, password);

        try {
            SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

            assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
            assertThat(first.getRowsInserted()).isEqualTo(2);
            assertThat(targetNames()).containsEntry("1", "ann").containsEntry("2", "bob");
            assertThat(targetAmount("2")).isEqualByComparingTo(new BigDecimal("20.00"));
        } finally {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + TABLE);
            }
        }
    }

    private Project savedProject(String url, String database, String user, String password) {
        DatabaseConfig source = new DatabaseConfig();
        source.setName("doris-it-src");
        source.setType(DatabaseType.DORIS);
        source.setRole(ConnectionRole.SOURCE);
        source.setCustomUrl(url);
        source.setDatabaseName(database);
        source.setSchemaName(database);
        source.setUsername(user);
        source.setPassword(cryptoUtil.encrypt(password));
        source = databaseConfigRepository.save(source);

        DatabaseConfig target = new DatabaseConfig();
        target.setName("doris-it-tgt");
        target.setType(DatabaseType.POSTGRESQL);
        target.setRole(ConnectionRole.TARGET);
        target.setHost(POSTGRES.getHost());
        target.setPort(POSTGRES.getMappedPort(5432));
        target.setDatabaseName("tgt");
        target.setSchemaName("public");
        target.setUsername(USER);
        target.setPassword(cryptoUtil.encrypt(PASSWORD));
        target = databaseConfigRepository.save(target);

        SyncConfig config = new SyncConfig();
        config.setCursorColumns(Map.of(TABLE.toUpperCase(), "UPDATED_AT"));
        Project project = new Project();
        project.setName("doris-source-it");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private Map<String, String> targetNames() throws Exception {
        Map<String, String> rows = new java.util.LinkedHashMap<>();
        DatabaseConfig target = databaseConfigRepository.findByName("doris-it-tgt").orElseThrow();
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement();
             // Doris reports the table and columns exactly as created (lower case), and the
             // target quotes the identifiers it is given, so those are the target's names.
             ResultSet rs = st.executeQuery("SELECT \"id\", \"name\" FROM \"public\".\""
                     + TABLE + "\" ORDER BY \"id\"")) {
            while (rs.next()) {
                rows.put(String.valueOf(rs.getLong(1)), rs.getString(2));
            }
        }
        return rows;
    }

    private BigDecimal targetAmount(String id) throws Exception {
        DatabaseConfig target = databaseConfigRepository.findByName("doris-it-tgt").orElseThrow();
        try (Connection conn = dataSourceManager.getConnection(target);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"amount\" FROM \"public\".\""
                     + TABLE + "\" WHERE \"id\" = " + id)) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        }
    }

    private static String currentDatabase(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
