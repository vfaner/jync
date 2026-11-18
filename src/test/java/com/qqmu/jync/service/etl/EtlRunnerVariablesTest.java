package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;

/**
 * A run resolves the task's placeholders once, before it opens a connection, and executes the
 * SQL the operator would have written by hand for that day.
 */
class EtlRunnerVariablesTest {

    private static final String SOURCE_URL =
            "jdbc:h2:mem:etl-var-src-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL =
            "jdbc:h2:mem:etl-var-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    /** Noon on 2026-03-05 in UTC, so the date built-ins are unambiguous. */
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-03-05T12:00:00Z"), ZoneId.of("UTC"));

    private final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
    private final EtlRunner runner = new EtlRunner(
            new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints,
            (config, connections) -> new JdbcEtlSource(connections.open(config), config), CLOCK);
    private final EtlConnectionProvider connections = config -> DriverManager.getConnection(
            config.getId() == null || config.getId() == 1L ? SOURCE_URL : TARGET_URL, "sa", "");

    private DatabaseConfig source;
    private DatabaseConfig target;

    @BeforeEach
    void setUp() throws SQLException {
        reset(SOURCE_URL);
        reset(TARGET_URL);
        source = config(1L);
        target = config(2L);
    }

    @AfterEach
    void tearDown() {
        checkpoints.values.clear();
    }

    @Test
    void theOwnersDateLimitsTheExtraction() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, dt VARCHAR(10))",
                "INSERT INTO src VALUES (1, '2026-03-04'), (2, '2026-03-05')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, dt VARCHAR(10))");

        EtlRunResult result = runner.run(task(
                "SELECT id, dt FROM src WHERE dt = '${date}'", "dst", EtlWriteMode.OVERWRITE),
                source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id FROM dst ORDER BY id")).containsExactly("2");
    }

    @Test
    void aCustomDefinitionAnswersTheReference() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, tenant VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'acme'), (2, 'other')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, tenant VARCHAR(20))");
        EtlTask task = task(
                "SELECT id, tenant FROM src WHERE tenant = '${tenant}'", "dst",
                EtlWriteMode.OVERWRITE);
        task.setVariables("tenant=acme");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id FROM dst ORDER BY id")).containsExactly("1");
    }

    @Test
    void preAndPostSqlSeeTheTargetTableAndTaskName() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'from source')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, name VARCHAR(20))",
                "INSERT INTO dst VALUES (99, 'stale')");
        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.APPEND);
        task.setName("nightly orders");
        task.setPreSql("DELETE FROM ${targetTable} WHERE id = 99");
        task.setPostSql("UPDATE ${targetTable} SET name = '${taskName}'");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id, name FROM dst ORDER BY id"))
                .containsExactly("1|nightly orders");
    }

    /**
     * The target connection, not the task, may name the schema. {@code ${targetTable}} must then
     * carry that schema: a pre-sql that deletes through the bare name would empty a same-named
     * table in the session's default schema instead of the one the copy writes.
     */
    @Test
    void preSqlTargetsTheQualifiedTableNotASameNamedTableInTheDefaultSchema() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        execute(TARGET_URL, "CREATE SCHEMA ODS", "CREATE TABLE ODS.dst (id INT)",
                "CREATE TABLE dst (id INT)", "INSERT INTO dst VALUES (99)");
        DatabaseConfig targetWithSchema = config(2L);
        targetWithSchema.setSchemaName("ODS");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setPreSql("DELETE FROM ${targetTable}");

        EtlRunResult result = runner.run(task, source, targetWithSchema, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id FROM ODS.dst")).containsExactly("1");
        assertThat(rows("SELECT id FROM dst")).containsExactly("99");
    }

    @Test
    void theRangeConditionUsesTheOwnersDate() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, dt VARCHAR(10))",
                "INSERT INTO src VALUES (1, '2026-03-05')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, dt VARCHAR(10))",
                "INSERT INTO dst VALUES (8, '2026-03-04'), (9, '2026-03-05')");
        EtlTask task = task("SELECT id, dt FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("dt = '${date}'");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id FROM dst ORDER BY id")).containsExactly("1", "8");
    }

    /** An unanswered placeholder is a configuration defect, found before anything is opened. */
    @Test
    void anUnknownVariableFailsAsProgramWithoutOpeningAConnection() {
        EtlTask task = task(
                "SELECT id FROM src WHERE dt = '${nope}'", "dst", EtlWriteMode.OVERWRITE);
        EtlConnectionProvider forbidden = config -> {
            throw new AssertionError("no connection may be opened for an unknown variable");
        };

        EtlRunResult result = runner.run(task, source, target, forbidden);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.var.unknown:nope");
    }

    /**
     * The run must judge the SQL it will actually execute: a definition that empties the range
     * condition is a defect found before any connection opens, not a target-side syntax error.
     */
    @Test
    void aDefinitionThatEmptiesTheRangeConditionFailsAsProgramWithoutOpeningAConnection() {
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("${cond}");
        task.setVariables("cond=");
        EtlConnectionProvider forbidden = config -> {
            throw new AssertionError("no connection may be opened for an empty range condition");
        };

        EtlRunResult result = runner.run(task, source, target, forbidden);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.range.required");
    }

    @Test
    void aDefinitionThatEmptiesTheExtractionFailsAsProgramWithoutOpeningAConnection() {
        EtlTask task = task("${sql}", "dst", EtlWriteMode.OVERWRITE);
        task.setVariables("sql=");
        EtlConnectionProvider forbidden = config -> {
            throw new AssertionError("no connection may be opened for an empty extraction");
        };

        EtlRunResult result = runner.run(task, source, target, forbidden);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.sql.required");
    }

    @Test
    void theSavedTaskKeepsItsPlaceholdersAfterARun() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, dt VARCHAR(10))",
                "INSERT INTO src VALUES (2, '2026-03-05')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, dt VARCHAR(10))");
        EtlTask task = task(
                "SELECT id, dt FROM src WHERE dt = '${date}'", "dst", EtlWriteMode.OVERWRITE);

        runner.run(task, source, target, connections);

        assertThat(task.getSourceSql())
                .isEqualTo("SELECT id, dt FROM src WHERE dt = '${date}'");
    }

    // --- helpers ------------------------------------------------------------------------

    private static EtlTask task(String sql, String table, EtlWriteMode mode) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceSql(sql);
        task.setTargetTable(table);
        task.setWriteMode(mode);
        return task;
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static void reset(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static List<String> rows(String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static final class MemoryCheckpoints implements EtlCheckpointStore {
        private final Map<Long, EtlCheckpointValue> values = new HashMap<>();

        @Override
        public EtlCheckpointValue load(Long taskId) {
            return values.get(taskId);
        }

        @Override
        public void save(Long taskId, int jdbcType, String value) {
            values.put(taskId, new EtlCheckpointValue(jdbcType, value));
        }

        @Override
        public void clear(Long taskId) {
            values.remove(taskId);
        }
    }
}
