package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
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
 * Runs the ETL copy loop against two H2 databases. The source is read through the customer's
 * SQL, the target is written row by row; nothing here touches the real-time sync engine.
 */
class EtlRunnerTest {

    private static final String SOURCE_URL =
            "jdbc:h2:mem:etl-src-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL =
            "jdbc:h2:mem:etl-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private final MemoryCheckpoints checkpoints = new MemoryCheckpoints();
    private final EtlRunner runner = new EtlRunner(
            new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints);
    private final EtlConnectionProvider connections = config -> {
        String url = config.getId() == null ? TARGET_URL : config.getId() == 1L
                ? SOURCE_URL : TARGET_URL;
        return DriverManager.getConnection(url, "sa", "");
    };

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
    void overwriteReplacesTheTargetContents() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO dst VALUES (99, 'stale')");

        EtlRunResult result = runner.run(task("SELECT id, name FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(3);
        assertThat(result.rowsWritten()).isEqualTo(3);
        assertThat(rows("SELECT id, name FROM dst ORDER BY id"))
                .containsExactly("1|a", "2|b", "3|c");
    }

    @Test
    void appendKeepsExistingRows() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO dst VALUES (99, 'old')");

        EtlRunResult result = runner.run(task("SELECT id, name FROM src", "dst",
                EtlWriteMode.APPEND), source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(rows("SELECT id FROM dst ORDER BY id")).containsExactly("1", "2", "99");
    }

    @Test
    void createsTheTargetTableWhenItDoesNotExist() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a')");

        EtlRunResult result = runner.run(task("SELECT id, name FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(rows("SELECT id, name FROM dst")).containsExactly("1|a");
    }

    @Test
    void rangeOverwriteDeletesOnlyTheConfiguredRange() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, name VARCHAR(20))",
                "INSERT INTO dst VALUES (1, 'old'), (2, 'old'), (99, 'keep')");

        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("id < 4");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(rows("SELECT id, name FROM dst ORDER BY id"))
                .containsExactly("1|a", "2|b", "3|c", "99|keep");
    }

    @Test
    void appendResumesAfterTheCheckpointedKey() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd'), (5, 'e')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO dst VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        checkpoints.save(7L, Types.INTEGER, "3");

        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(rows("SELECT id FROM dst ORDER BY id"))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(checkpoints.load(7L).value()).isEqualTo("5");
    }

    /**
     * A continuation reads only the keys after the checkpoint. Running the pre-sql again would
     * let its documented cleanup DELETE remove the rows the first run already committed, and the
     * continuation query would never bring them back.
     */
    @Test
    void aContinuationRunDoesNotReplayThePreSqlThatWouldDeleteItsCommittedRows()
            throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd'), (5, 'e')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO dst VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        checkpoints.save(7L, Types.INTEGER, "3");

        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");
        task.setPreSql("DELETE FROM dst");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(rows("SELECT id FROM dst ORDER BY id"))
                .containsExactly("1", "2", "3", "4", "5");
    }

    /** The skip belongs to continuations only: a first run still relies on its pre-sql. */
    @Test
    void aFirstRunStillRunsThePreSql() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY)", "INSERT INTO src VALUES (4)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY)",
                "INSERT INTO dst VALUES (99)");

        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");
        task.setPreSql("DELETE FROM dst WHERE id = 99");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(rows("SELECT id FROM dst ORDER BY id")).containsExactly("4");
    }

    /**
     * The customer re-runs the same extraction to refresh a table: rows whose key already
     * exists must take the new values, the missing ones must be inserted, and rows outside the
     * result must survive.
     */
    @Test
    void upsertUpdatesExistingKeysAndInsertsTheMissingRows() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO dst VALUES (1, 'old'), (2, 'stale'), (99, 'keep')");

        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("id");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsWritten()).isEqualTo(3);
        assertThat(rows("SELECT id, name FROM dst ORDER BY id"))
                .containsExactly("1|a", "2|b", "3|c", "99|keep");
    }

    @Test
    void upsertMatchesOnEveryConfiguredKeyColumn() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (tenant INT, id INT, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 10, 'a'), (2, 10, 'b')");
        execute(TARGET_URL, "CREATE TABLE dst (tenant INT, id INT, name VARCHAR(20))",
                "INSERT INTO dst VALUES (1, 10, 'old'), (1, 11, 'keep')");

        EtlTask task = task("SELECT tenant, id, name FROM src", "dst", EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("tenant, id");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(rows("SELECT tenant, id, name FROM dst ORDER BY tenant, id"))
                .containsExactly("1|10|a", "1|11|keep", "2|10|b");
    }

    @Test
    void upsertCreatesTheTargetTableWithTheConfiguredPrimaryKey() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a')");
        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("id");

        assertThat(runner.run(task, source, target, connections).success()).isTrue();

        // The key the upsert matches on must actually be enforced: a second row with the
        // same key cannot slip in.
        assertThatThrownBy(() -> execute(TARGET_URL, "INSERT INTO dst VALUES (1, 'dup')"))
                .isInstanceOf(SQLException.class);
    }

    /**
     * A NULL key can never match a row: the UPDATE predicate is never true and a MySQL unique
     * index accepts repeated NULLs, so the emulation would insert another copy on every run.
     * The run must say which column is at fault instead of duplicating rows silently.
     */
    @Test
    void upsertRejectsARowWhoseConfiguredKeyIsNull() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, name VARCHAR(20))",
                "INSERT INTO src VALUES (NULL, 'a'), (1, 'b')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT, name VARCHAR(20))");
        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("id");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        // The label is the driver's: H2 folds an unquoted result label to upper case.
        assertThat(result.errorMessage())
                .contains("error.etl.upsertKey.null")
                .containsIgnoringCase("id");
        assertThat(rows("SELECT id FROM dst")).isEmpty();
    }

    @Test
    void upsertWithoutAKeyColumnIsRejectedBeforeTheTargetIsTouched() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)", "INSERT INTO dst VALUES (7)");

        EtlRunResult result = runner.run(task("SELECT id FROM src", "dst",
                EtlWriteMode.UPSERT), source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(rows("SELECT id FROM dst")).containsExactly("7");
    }

    @Test
    void upsertWithAKeyMissingFromTheResultFailsBeforeTheTableIsCreated() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.UPSERT);
        task.setUpsertKeyColumns("tenant_id");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).contains("tenant_id");
        try (Connection targetConnection = DriverManager.getConnection(TARGET_URL, "sa", "")) {
            assertThat(EtlSqlSupport.exists(targetConnection, "dst")).isFalse();
        }
    }

    @Test
    void overwriteIgnoresAndClearsTheCheckpoint() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT PRIMARY KEY, name VARCHAR(20))");
        checkpoints.save(7L, Types.INTEGER, "99");

        EtlTask task = task("SELECT id, name FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setResumeKeyColumn("id");
        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsWritten()).isEqualTo(2);
        assertThat(checkpoints.load(7L)).isNull();
    }

    @Test
    void aScriptPreparesTheSourceBeforeTheLastSelect() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT PRIMARY KEY, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b')");

        EtlRunResult result = runner.run(task(
                "CREATE TEMP TABLE picked (id INT, name VARCHAR(20));"
                        + " INSERT INTO picked SELECT id, name FROM src WHERE id > 1;"
                        + " SELECT id, name FROM picked",
                "dst", EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(1);
        assertThat(rows("SELECT id, name FROM dst")).containsExactly("2|b");
    }

    @Test
    void aBrokenSourceSqlIsAttributedToTheSource() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");

        EtlRunResult result = runner.run(task("SELECT missing FROM nowhere", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.SOURCE);
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void aTargetThatCannotTakeTheRowIsAttributedToTheTarget() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT, name VARCHAR(20))",
                "INSERT INTO src VALUES (1, 'a')");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");

        EtlRunResult result = runner.run(task("SELECT id, name FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
    }

    /**
     * Pre-sql runs on the target connection, so its failure is the target's. Reporting it as a
     * program error sends the operator looking at the wrong side of the copy.
     */
    /**
     * The documented pre-sql example is a cleanup DELETE against the target. On a first run
     * the target table does not exist yet, so the run must create it before the pre-sql
     * executes; otherwise the very example the form suggests fails every first run.
     */
    @Test
    void preSqlMayCleanATargetTableTheRunItselfCreates() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1), (2)");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setPreSql("DELETE FROM dst");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).as("run failed: %s", result.errorMessage()).isTrue();
        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(result.rowsWritten()).isEqualTo(2);
    }

    @Test
    void aFailingPreSqlIsAttributedToTheTarget() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setPreSql("DELETE FROM not_there");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
    }

    /** Post-sql is target-side too, including after the last batch was flushed. */
    @Test
    void aFailingPostSqlIsAttributedToTheTarget() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setPostSql("UPDATE not_there SET id = 1");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
    }

    /**
     * With no rows the copy loop never runs, so the stage would still read as the source. The
     * post-sql statement still executes on the target and its failure belongs there.
     */
    @Test
    void aFailingPostSqlAfterAnEmptySourceIsStillAttributedToTheTarget() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.OVERWRITE);
        task.setPostSql("UPDATE not_there SET id = 1");

        EtlRunResult result = runner.run(task, source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
    }

    /**
     * A driver that fails while closing the source must not turn a finished copy into a thrown
     * exception: the caller would then never record the run and the operator would see the task
     * as never having run.
     */
    @Test
    void aSourceThatCannotBeClosedDoesNotDiscardTheRun() throws SQLException {
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlSourceFactory factory = (config, provider) -> new EtlSource() {
            @Override
            public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
                return new EtlSourceCursor() {
                    @Override
                    public List<EtlSourceColumn> columns() {
                        return List.of(new EtlSourceColumn(
                                "id", Types.INTEGER, "INTEGER", null, null, true));
                    }

                    @Override
                    public int resumeKeyIndex() {
                        return -1;
                    }

                    @Override
                    public boolean next() {
                        return false;
                    }

                    @Override
                    public Object getObject(int oneBasedIndex) {
                        return null;
                    }

                    @Override
                    public String getString(int oneBasedIndex) {
                        return null;
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                throw new IllegalStateException("driver went away");
            }
        };
        EtlRunner guarded = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints, factory);

        EtlRunResult result = guarded.run(task("SELECT id FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isTrue();
    }

    /**
     * A driver error raised while fetching the next row is a source failure. The loop leaves the
     * stage at TARGET after each row's insert, so a next() that throws was reported as the
     * target side and sent the operator to the wrong connection.
     */
    @Test
    void aSourceFailureWhileFetchingTheNextRowIsAttributedToTheSource() throws SQLException {
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");
        EtlSourceFactory factory = (config, provider) -> new EtlSource() {
            @Override
            public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
                return new EtlSourceCursor() {
                    private int row;

                    @Override
                    public List<EtlSourceColumn> columns() {
                        return List.of(new EtlSourceColumn(
                                "id", Types.INTEGER, "INTEGER", null, null, true));
                    }

                    @Override
                    public int resumeKeyIndex() {
                        return -1;
                    }

                    @Override
                    public boolean next() throws SQLException {
                        if (row++ == 0) {
                            return true;
                        }
                        throw new SQLException("source connection lost");
                    }

                    @Override
                    public Object getObject(int oneBasedIndex) {
                        return 1;
                    }

                    @Override
                    public String getString(int oneBasedIndex) {
                        return "1";
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        EtlRunner guarded = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints, factory);

        EtlRunResult result = guarded.run(task("SELECT id FROM src", "dst",
                EtlWriteMode.OVERWRITE), source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.SOURCE);
    }

    @Test
    void aRangeOverwriteWithoutAConditionIsRejectedBeforeTouchingTheTarget() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)", "INSERT INTO dst VALUES (1)");

        EtlRunResult result = runner.run(task("SELECT id FROM src", "dst",
                EtlWriteMode.RANGE_OVERWRITE), source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(rows("SELECT id FROM dst")).containsExactly("1");
    }

    @Test
    void aResumeKeyMissingFromTheSourceResultFailsOnTheSourceQuery() throws SQLException {
        execute(SOURCE_URL, "CREATE TABLE src (id INT)", "INSERT INTO src VALUES (1)");
        execute(TARGET_URL, "CREATE TABLE dst (id INT)");

        EtlTask task = task("SELECT id FROM src", "dst", EtlWriteMode.APPEND);
        task.setResumeKeyColumn("not_there");
        EtlRunResult result = runner.run(task, source, target, connections);

        // The continuation wrapper is itself a source query, so the database rejects the
        // unknown column there. Nothing was read and, crucially, nothing was deleted.
        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.SOURCE);
        assertThat(result.errorMessage()).containsIgnoringCase("not_there");
        assertThat(rows("SELECT id FROM dst")).isEmpty();
    }

    /**
     * PostgreSQL-family drivers abort the whole transaction when any statement fails. The probe
     * for a missing target is expected to fail, so without a rollback the CREATE TABLE that
     * follows would answer "current transaction is aborted" and auto-create would never work on
     * those targets. The probe runs before anything is written, so the rollback costs nothing.
     */
    @Test
    void aFailedExistenceProbeRollsBackTheAbortedTargetTransaction() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("relation \"dst\" does not exist", "42P01", 0));

        assertThat(EtlSqlSupport.exists(connection, "dst")).isFalse();
        verify(connection).rollback();
    }

    @Test
    void aPrivilegeErrorOnTheProbeSurfacesInsteadOfCreatingTheTable() throws SQLException {
        // "Table does not exist" and "you may not read it" are the same boolean unless the
        // error is classified. Treating the second as the first sends the runner into
        // CREATE TABLE over an object the account cannot even see — and turns a permission
        // problem into a confusing "table already exists" or DDL failure.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("permission denied for table dst", "42501", 0));

        assertThatThrownBy(() -> EtlSqlSupport.exists(connection, "dst"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        verify(connection).rollback();
    }

    @Test
    void theDriversMissingTableStatesAllCountAsAbsent() throws SQLException {
        // MySQL/SQL Server/DB2/Oracle each spell "no such table" differently; the probe has
        // to recognise all of them, otherwise a perfectly normal auto-create is refused.
        for (SQLException missing : List.of(
                new SQLException("Table 'db.dst' doesn't exist", "42S02", 1146),
                new SQLException("Invalid object name 'dst'.", "S0002", 208),
                new SQLException("table or view does not exist", "42000", 942),
                new SQLException("undefined name", "42704", -204),
                new SQLException("Table \"DST\" not found", "42S02", 42102))) {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenThrow(missing);

            assertThat(EtlSqlSupport.exists(connection, "dst")).as(missing.getMessage()).isFalse();
        }
    }

    @Test
    void analysisEngineTargetsAlsoCountAsAbsentWhenTheTableIsMissing() throws SQLException {
        // ClickHouse, Hive, Impala and HANA are all ETL-writable, and the documented first
        // run creates the target table. Their "no such table" errors do not carry the
        // SQL-standard states the probe knows, so they must be read from the message.
        for (SQLException missing : List.of(
                new SQLException("Code: 60. DB::Exception: Table default.dst does not exist."
                        + " (UNKNOWN_TABLE)", null, 60),
                new SQLException("Error while compiling statement: FAILED: SemanticException"
                        + " [Error 10001]: Table not found dst", "42000", 10001),
                new SQLException("AnalysisException: Table does not exist: dst"),
                new SQLException("SAP DBTech JDBC: [259]: invalid table name: dst"))) {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenThrow(missing);

            assertThat(EtlSqlSupport.exists(connection, "dst")).as(missing.getMessage()).isFalse();
        }
    }

    @Test
    void aDeadlockIsNotAnAbsentTable() throws SQLException {
        // Oracle ORA-00060 shares the numeric code ClickHouse gives UNKNOWN_TABLE. The
        // vendor code alone must never classify an error as a missing table.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(
                new SQLException("ORA-00060: deadlock detected while waiting for resource",
                        "60000", 60));

        assertThatThrownBy(() -> EtlSqlSupport.exists(connection, "dst"))
                .isInstanceOf(SQLException.class);
        verify(connection).rollback();
    }

    @Test
    void aSourceThatCannotOpenIsBlamedOnTheSource() {
        // The stage still said PROGRAM when the source connection was opened, so an
        // unreachable source database was reported as a program error. The operator then
        // hunts for a bad query instead of checking the source endpoint.
        EtlRunner failing = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints,
                (config, conns) -> {
                    throw new SQLException("source refused");
                });

        EtlRunResult result = failing.run(task("SELECT 1", "dst", EtlWriteMode.APPEND),
                source, target, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.SOURCE);
    }

    @Test
    void aTargetThatCannotOpenIsBlamedOnTheTarget() {
        EtlRunner failing = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints,
                (config, conns) -> mock(EtlSource.class));

        EtlConnectionProvider targetDown = config -> {
            throw new SQLException("target refused");
        };
        EtlRunResult result = failing.run(task("SELECT 1", "dst", EtlWriteMode.APPEND),
                source, target, targetDown);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
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
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static List<String> rows(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                values.add(row.toString());
            }
        }
        return values;
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
