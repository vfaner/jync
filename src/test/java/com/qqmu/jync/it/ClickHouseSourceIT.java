package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
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
 * Real-database matrix: ClickHouse 24.8 LTS as a polling source into PostgreSQL 16.
 *
 * <p>ClickHouse has no enforced unique constraint, and {@code KeylessEngineMetadataReader}
 * deliberately ignores the driver's sort key. The initial load therefore takes the keyless
 * OFFSET walk — the only full-load route these analytic engines have — and this test pins
 * that walk across more than one page: 2500 rows at the reduced {@code chunk-size} of 1000,
 * so a walk that stopped at its first page would leave 1000 target rows, not 2500.
 *
 * <p>The column set covers what this family actually returns through JDBC: {@code Int64},
 * {@code String}, {@code Decimal(10,2)}, {@code DateTime64(3,'UTC')} and a nullable string.
 * The target must create a compatible column for each and carry the values over, including
 * the NULL note.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:clickhouseit;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE",
        "sync.ai.enabled=false",
        "app.update-check.enabled=false",
        // Three pages for the row count below; the default 100000 would hide a paging bug.
        "sync.chunk-size=1000"
})
class ClickHouseSourceIT {

    private static final String USER = "test";
    private static final String PASSWORD = "test";
    private static final String SOURCE_DB = "src";
    private static final String TARGET_TABLE = "CH_EVENTS";
    private static final int ROWS = 2500;

    @Container
    private static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"))
                    .withDatabaseName(SOURCE_DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

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
    void aKeylessClickHouseTableLoadsAcrossOffsetPagesAndStaysIdle() throws Exception {
        createSourceTable();
        Project project = savedProject();

        SyncResult first = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(first.isSuccess()).as("首轮错误: %s", first.getErrors()).isTrue();
        assertThat(first.getRowsInserted()).isEqualTo(ROWS);
        assertThat(targetRowCount()).isEqualTo(ROWS);
        assertThat(targetHasPrimaryKey()).as("ClickHouse tables are synced keyless").isFalse();
        assertTargetShape();
        assertRowValues();

        // The timestamp cursor advanced to MAX(UPDATED_AT). A keyless table gets no safety
        // margin (a replay would be a plain INSERT and duplicate rows), so an idle cycle
        // reads nothing at all.
        SyncResult second = syncEngine.runCycle(contextFactory.build(project, "it-lock", null));

        assertThat(second.isSuccess()).as("空转错误: %s", second.getErrors()).isTrue();
        assertThat(second.getRowsInserted()).isZero();
        assertThat(second.getRowsUpdated()).isZero();
        assertThat(targetRowCount()).isEqualTo(ROWS);
    }

    private void createSourceTable() throws Exception {
        Class.forName(DatabaseType.CLICKHOUSE.getDriverClassName());
        try (Connection conn = DriverManager.getConnection(sourceUrl(), USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TARGET_TABLE);
            st.execute("CREATE TABLE " + TARGET_TABLE + " ("
                    + " ID Int64,"
                    + " NAME String,"
                    + " AMOUNT Decimal(10,2),"
                    + " UPDATED_AT DateTime64(3,'UTC'),"
                    + " NOTE Nullable(String))"
                    + " ENGINE = MergeTree ORDER BY ID");
            st.execute("INSERT INTO " + TARGET_TABLE
                    + " SELECT number + 1,"
                    + " concat('name-', toString(number + 1)),"
                    + " toDecimal64((number + 1) * 1.25, 2),"
                    + " toDateTime64('2026-01-01 00:00:00', 3, 'UTC')"
                    + " + toIntervalSecond(number),"
                    // Row 1 (number 0) carries the NULL note that the nullable column exists
                    // to prove survives the copy.
                    + " if(number % 10 = 0, NULL, concat('note-', toString(number + 1)))"
                    + " FROM numbers(" + ROWS + ")");
        }
    }

    private Project savedProject() {
        DatabaseConfig source = savedConfig("ch-it-src", DatabaseType.CLICKHOUSE, SOURCE_DB,
                SOURCE_DB, CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123),
                ConnectionRole.SOURCE);
        DatabaseConfig target = savedConfig("ch-it-tgt", DatabaseType.POSTGRESQL, "tgt",
                "public", POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                ConnectionRole.TARGET);
        SyncConfig config = new SyncConfig();
        // Explicit rather than auto-detected, so the test pins the polling path it asserts.
        config.setCursorColumns(Map.of(TARGET_TABLE, "UPDATED_AT"));
        Project project = new Project();
        project.setName("clickhouse-source-it");
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
        config.setUsername(USER);
        config.setPassword(cryptoUtil.encrypt(PASSWORD));
        return databaseConfigRepository.save(config);
    }

    private long targetRowCount() throws SQLException {
        try (Connection conn = targetConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM \"public\".\"" + TARGET_TABLE + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean targetHasPrimaryKey() throws SQLException {
        try (Connection conn = targetConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_index i"
                             + " JOIN pg_class c ON c.oid = i.indrelid"
                             + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                             + " WHERE n.nspname = 'public' AND c.relname = ? AND i.indisprimary")) {
            ps.setString(1, TARGET_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * The target DDL must be valid for every ClickHouse type the source reports: Int64 →
     * BIGINT, String → TEXT (the driver reports width 0), Decimal(10,2) → NUMERIC(10,2),
     * DateTime64 → TIMESTAMP, Nullable(String) → nullable TEXT.
     */
    private void assertTargetShape() throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> nullable = new LinkedHashMap<>();
        try (Connection conn = targetConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                             + " WHERE table_schema = 'public' AND table_name = ?"
                             + " ORDER BY ordinal_position")) {
            ps.setString(1, TARGET_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                    nullable.put(rs.getString(1), rs.getString(3));
                }
            }
        }
        assertThat(types).containsEntry("ID", "bigint")
                .containsEntry("NAME", "text")
                .containsEntry("AMOUNT", "numeric")
                .containsEntry("UPDATED_AT", "timestamp without time zone")
                .containsEntry("NOTE", "text");
        assertThat(nullable).containsEntry("NOTE", "YES").containsEntry("ID", "NO");
    }

    private void assertRowValues() throws SQLException {
        try (Connection conn = targetConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT \"ID\", \"NAME\", \"AMOUNT\", \"UPDATED_AT\", \"NOTE\""
                             + " FROM \"public\".\"" + TARGET_TABLE + "\""
                             + " WHERE \"ID\" IN (1, 2, 1000, " + ROWS + ")"
                             + " ORDER BY \"ID\"")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("ID")).isEqualTo(1L);
                assertThat(rs.getString("NAME")).isEqualTo("name-1");
                assertThat(rs.getBigDecimal("AMOUNT")).isEqualByComparingTo(new BigDecimal("1.25"));
                assertThat(rs.getTimestamp("UPDATED_AT"))
                        .isEqualTo(Timestamp.valueOf("2026-01-01 00:00:00"));
                assertThat(rs.getObject("NOTE")).as("Nullable(String) NULL must survive").isNull();

                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("NAME")).isEqualTo("name-2");
                assertThat(rs.getString("NOTE")).isEqualTo("note-2");

                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("ID")).isEqualTo(1000L);
                assertThat(rs.getBigDecimal("AMOUNT"))
                        .isEqualByComparingTo(new BigDecimal("1250.00"));

                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("ID")).isEqualTo((long) ROWS);
                assertThat(rs.getString("NAME")).isEqualTo("name-" + ROWS);
                assertThat(rs.next()).as("no duplicate rows from the OFFSET walk").isFalse();
            }
        }
    }

    private Connection targetConnection() throws SQLException {
        DatabaseConfig target = databaseConfigRepository.findByName("ch-it-tgt").orElseThrow();
        return dataSourceManager.getConnection(target);
    }

    private static String sourceUrl() {
        return "jdbc:clickhouse://" + CLICKHOUSE.getHost() + ":"
                + CLICKHOUSE.getMappedPort(8123) + "/" + SOURCE_DB;
    }
}
