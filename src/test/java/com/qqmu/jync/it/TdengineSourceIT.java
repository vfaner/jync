package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Real-database matrix: TDengine 3.3.6 as a polling source into PostgreSQL 16.
 *
 * <p>The source carries a column-level primary key, so the table's real key is
 * {@code [ts, id]} (the timestamp is always unique) and the target must recreate the composite
 * key. The VARCHAR column proves bytes are read as text, and the idle cycle proves the
 * timestamp cursor advances without shifting the wall clock.
 *
 * <p>TDengine 3.3.6 rejects a table-level {@code PRIMARY KEY (...)}; only the column-level
 * form creates. The fixture uses that form on purpose.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tdengineit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false",
        // Three pages for the row count below; the default 100000 would hide a paging bug.
        "sync.chunk-size=2"
})
class TdengineSourceIT {

    private static final String SOURCE_DB = "it_src";
    private static final String TABLE = "events";
    private static final int ROWS = 5;
    private static final String USER = "root";
    private static final String PASSWORD = "taosdata";

    @Container
    private static final GenericContainer<?> TDENGINE =
            new GenericContainer<>(DockerImageName.parse("tdengine/tdengine:3.3.6.0"))
                    .withExposedPorts(6041)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(3)));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("tgt").withUsername("test").withPassword("test");

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository databaseConfigRepository;
    @Autowired private SyncContextFactory contextFactory;
    @Autowired private SyncEngine syncEngine;
    @Autowired private MetadataReaderFactory readerFactory;
    @Autowired private CryptoUtil cryptoUtil;

    @Test
    void aTdengineTableLoadsWithItsCompositeKeyIntoPostgres() throws Exception {
        createSource();
        Project project = savedProject();

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(ROWS);
        assertThat(targetRowCount()).isEqualTo(ROWS);
        assertThat(targetPrimaryKey()).containsExactly("ts", "id");
        assertThat(targetTypes()).containsEntry("name", "character varying");
        assertThat(targetValues()).containsExactly("evt-1", "evt-2", "evt-3", "evt-4", "evt-5");

        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(second.isSuccess()).as("空转错误: %s", second.getErrors()).isTrue();
        assertThat(second.getRowsInserted()).isZero();
        assertThat(second.getRowsUpdated()).isZero();
        assertThat(targetRowCount()).isEqualTo(ROWS);

        // 超级表进入表清单,子表不进入。
        MetadataReader reader = readerFactory.forType(DatabaseType.TDENGINE);
        try (Connection conn = DriverManager.getConnection(
                "jdbc:TAOS-WS://" + TDENGINE.getHost() + ":"
                        + TDENGINE.getMappedPort(6041) + "/" + SOURCE_DB, USER, PASSWORD)) {
            assertThat(reader.listTableNames(conn, SOURCE_DB))
                    .contains(TABLE, "meters").doesNotContain("m1");
        }
    }

    private void createSource() throws Exception {
        Class.forName(DatabaseType.TDENGINE.getDriverClassName());
        try (Connection conn = DriverManager.getConnection(
                "jdbc:TAOS-WS://" + TDENGINE.getHost() + ":"
                        + TDENGINE.getMappedPort(6041) + "/", USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SOURCE_DB);
            // Column-level PRIMARY KEY: TDengine 3.3.6 rejects a table-level key.
            st.execute("CREATE TABLE " + SOURCE_DB + "." + TABLE
                    + " (ts TIMESTAMP, id INT PRIMARY KEY, name VARCHAR(32))");
            for (int i = 1; i <= ROWS; i++) {
                st.execute("INSERT INTO " + SOURCE_DB + "." + TABLE + " VALUES ('2026-01-01 00:00:0"
                        + i + ".000', " + i + ", 'evt-" + i + "')");
            }
            st.execute("CREATE STABLE " + SOURCE_DB + ".meters (ts TIMESTAMP, val DOUBLE)"
                    + " TAGS (sn VARCHAR(32))");
            st.execute("CREATE TABLE " + SOURCE_DB + ".m1 USING " + SOURCE_DB
                    + ".meters TAGS ('sn-1')");
        }
    }

    private Project savedProject() {
        DatabaseConfig source = savedConfig("td-it-src", DatabaseType.TDENGINE, SOURCE_DB,
                SOURCE_DB, TDENGINE.getHost(), TDENGINE.getMappedPort(6041),
                ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("td-it-tgt", DatabaseType.POSTGRESQL, "tgt",
                "public", POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        // Explicit: TDengine has no auto-detected cursor column name.
        config.setCursorColumns(Map.of(TABLE, "ts"));
        Project project = new Project();
        project.setName("tdengine-source-it");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        project.setSyncConfig(contextFactory.serializeConfig(config));
        project.setEnabled(false);
        return projectRepository.save(project);
    }

    private DatabaseConfig savedConfig(String name, DatabaseType type, String database,
            String schema, String host, int port, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(type);
        config.setRole(role);
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        config.setSchemaName(schema);
        config.setUsername(type == DatabaseType.TDENGINE ? USER : "test");
        config.setPassword(cryptoUtil.encrypt(type == DatabaseType.TDENGINE ? PASSWORD : "test"));
        return databaseConfigRepository.save(config);
    }

    private long targetRowCount() throws Exception {
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM public.events")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private java.util.List<String> targetPrimaryKey() throws Exception {
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT a.attname FROM pg_index i"
                             + " JOIN pg_attribute a ON a.attrelid = i.indrelid"
                             + " AND a.attnum = ANY(i.indkey)"
                             + " WHERE i.indrelid = 'public.events'::regclass AND i.indisprimary"
                             + " ORDER BY array_position(i.indkey, a.attnum)")) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (rs.next()) {
                keys.add(rs.getString(1));
            }
            return keys;
        }
    }

    private Map<String, String> targetTypes() throws Exception {
        Map<String, String> types = new LinkedHashMap<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_name, data_type FROM information_schema.columns"
                             + " WHERE table_schema = 'public' AND table_name = 'events'")) {
            while (rs.next()) {
                types.put(rs.getString(1), rs.getString(2));
            }
        }
        return types;
    }

    private java.util.List<String> targetValues() throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (Connection conn = targetConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM public.events ORDER BY id")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private Connection targetConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "test", "test");
    }
}
