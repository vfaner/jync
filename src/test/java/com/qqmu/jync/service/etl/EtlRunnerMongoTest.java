package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;

/**
 * The copy loop is driver-agnostic: it receives columns and rows from an {@link EtlSource}.
 * These runs feed it a Mongo-shaped source and an H2 target, so the write modes, the batch
 * commits, the checkpoint cadence and the error attribution are pinned without a server.
 */
class EtlRunnerMongoTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:etl-mongo-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final List<EtlSourceColumn> COLUMNS = List.of(
            new EtlSourceColumn("id", Types.INTEGER, "Int32", null, null, true),
            new EtlSourceColumn("name", Types.CLOB, "String", null, null, true));

    private final CountingCheckpoints checkpoints = new CountingCheckpoints();
    private FakeSource source;
    private FakeSourceFactory factory;
    private EtlRunner runner;

    @BeforeEach
    void setUp() throws SQLException {
        reset();
        source = new FakeSource(COLUMNS);
        factory = new FakeSourceFactory(source);
        runner = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints, factory);
    }

    @Test
    void overwriteReplacesTheTargetContentsThroughTheMongoSource() throws SQLException {
        source.rows(row(1, "a"), row(2, "b"));
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)",
                "INSERT INTO dst VALUES (99, 'stale')");

        EtlRunResult result = run(mongoTask(EtlWriteMode.OVERWRITE));

        assertThat(result.success()).isTrue();
        assertThat(result.rowsWritten()).isEqualTo(2);
        assertThat(rows()).containsExactly("1|a", "2|b");
    }

    @Test
    void appendKeepsTheTargetAndHandsTheCheckpointToTheSource() throws SQLException {
        source.rows(row(2, "b"));
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)",
                "INSERT INTO dst VALUES (1, 'a')");
        checkpoints.save(7L, Types.INTEGER, "1");
        EtlTask task = mongoTask(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        EtlRunResult result = run(task);

        assertThat(result.success()).isTrue();
        assertThat(source.checkpoint.value()).isEqualTo("1");
        assertThat(rows()).containsExactly("1|a", "2|b");
    }

    @Test
    void rangeOverwriteDeletesOnlyTheConfiguredRangeOnTheFirstRun() throws SQLException {
        source.rows(row(1, "a"), row(2, "b"));
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)",
                "INSERT INTO dst VALUES (1, 'old'), (99, 'keep')");
        EtlTask task = mongoTask(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("id < 4");
        task.setResumeKeyColumn("id");

        EtlRunResult result = run(task);

        assertThat(result.success()).isTrue();
        assertThat(rows()).containsExactly("1|a", "2|b", "99|keep");
    }

    @Test
    void aContinuationRangeOverwriteDoesNotDeleteAgain() throws SQLException {
        source.rows(row(2, "b"));
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)",
                "INSERT INTO dst VALUES (1, 'a')");
        checkpoints.save(7L, Types.INTEGER, "1");
        EtlTask task = mongoTask(EtlWriteMode.RANGE_OVERWRITE);
        task.setRangeCondition("id < 4");
        task.setResumeKeyColumn("id");

        EtlRunResult result = run(task);

        assertThat(result.success()).isTrue();
        assertThat(rows()).containsExactly("1|a", "2|b");
    }

    @Test
    void aSourceFailureIsAttributedToTheSource() throws SQLException {
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)");
        source.failure = new EtlSourceException(
                "etl.precheck.mongo.connection.error", "connection refused");

        EtlRunResult result = run(mongoTask(EtlWriteMode.OVERWRITE));

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.SOURCE);
        assertThat(result.errorMessage()).isEqualTo("connection refused");
        assertThat(source.closed).isTrue();
    }

    @Test
    void aTargetThatCannotTakeTheRowIsAttributedToTheTarget() throws SQLException {
        source.rows(row(1, "a"));
        execute("CREATE TABLE dst (id INT PRIMARY KEY)");

        EtlRunResult result = run(mongoTask(EtlWriteMode.OVERWRITE));

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.TARGET);
    }

    @Test
    void everyFullBatchAndTheTailSaveACheckpoint() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            rows.add(row(i, "row-" + i));
        }
        source.rows(rows.toArray(Object[][]::new));
        EtlTask task = mongoTask(EtlWriteMode.APPEND);
        task.setResumeKeyColumn("id");

        EtlRunResult result = run(task);

        assertThat(result.success()).isTrue();
        assertThat(result.rowsWritten()).isEqualTo(501);
        assertThat(checkpoints.saves).isEqualTo(2);
        assertThat(checkpoints.load(7L).value()).isEqualTo("501");
    }

    @Test
    void anInvalidFieldFailsBeforeTheTargetIsDeleted() throws SQLException {
        execute("CREATE TABLE dst (id INT PRIMARY KEY, name CLOB)",
                "INSERT INTO dst VALUES (1, 'keep')");
        source.failure = new EtlProgramException("error.etl.mongo.field.invalid: bad-name");

        EtlRunResult result = run(mongoTask(EtlWriteMode.OVERWRITE));

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(rows()).containsExactly("1|keep");
        assertThat(source.closed).isTrue();
    }

    @Test
    void aMissingCollectionIsRejectedBeforeTheSourceIsOpened() throws SQLException {
        EtlTask task = mongoTask(EtlWriteMode.OVERWRITE);
        task.setSourceCollection(" ");

        EtlRunResult result = run(task);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.collection.required");
        assertThat(factory.opened).isZero();
    }

    @Test
    void anInvalidFilterIsRejectedBeforeTheSourceIsOpened() throws SQLException {
        EtlTask task = mongoTask(EtlWriteMode.OVERWRITE);
        task.setSourceFilter("{\"status\":");

        EtlRunResult result = run(task);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.mongo.filter.invalid");
        assertThat(factory.opened).isZero();
    }

    @Test
    void aNativeConnectionCannotBeTheTarget() throws SQLException {
        DatabaseConfig nativeTarget = mongoConfig(2L);

        EtlRunResult result = runner.run(
                mongoTask(EtlWriteMode.OVERWRITE), mongoConfig(1L), nativeTarget, connections());

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage()).isEqualTo("error.etl.target.readOnly");
    }

    // --- helpers ------------------------------------------------------------------------

    private EtlRunResult run(EtlTask task) {
        return runner.run(task, mongoConfig(1L), config(2L), connections());
    }

    private static EtlConnectionProvider connections() {
        return config -> DriverManager.getConnection(TARGET_URL, "sa", "");
    }

    private static EtlTask mongoTask(EtlWriteMode mode) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceCollection("orders");
        task.setSourceSql("");
        task.setTargetTable("dst");
        task.setWriteMode(mode);
        return task;
    }

    private static DatabaseConfig mongoConfig(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static void reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static List<String> rows() throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name FROM dst ORDER BY id")) {
            while (rs.next()) {
                values.add(rs.getString(1) + "|" + rs.getString(2));
            }
        }
        return values;
    }

    private static final class FakeSource implements EtlSource {
        private final List<EtlSourceColumn> columns;
        private List<Object[]> rows = List.of();
        private EtlCheckpointValue checkpoint;
        private RuntimeException failure;
        private boolean closed;

        FakeSource(List<EtlSourceColumn> columns) {
            this.columns = columns;
        }

        void rows(Object[]... rows) {
            this.rows = Arrays.asList(rows);
        }

        @Override
        public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
            this.checkpoint = checkpoint;
            if (failure != null) {
                throw failure;
            }
            int keyIndex = -1;
            if (task.getResumeKeyColumn() != null) {
                for (int i = 0; i < columns.size(); i++) {
                    if (columns.get(i).name().equals(task.getResumeKeyColumn().trim())) {
                        keyIndex = i + 1;
                        break;
                    }
                }
            }
            return new FakeCursor(columns, rows, keyIndex);
        }

        @Override
        public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
            throw new UnsupportedOperationException("not used by the runner");
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeCursor implements EtlSourceCursor {
        private final List<EtlSourceColumn> columns;
        private final List<Object[]> rows;
        private final int keyIndex;
        private int index = -1;

        FakeCursor(List<EtlSourceColumn> columns, List<Object[]> rows, int keyIndex) {
            this.columns = columns;
            this.rows = rows;
            this.keyIndex = keyIndex;
        }

        @Override
        public List<EtlSourceColumn> columns() {
            return columns;
        }

        @Override
        public int resumeKeyIndex() {
            return keyIndex;
        }

        @Override
        public boolean next() {
            index++;
            return index < rows.size();
        }

        @Override
        public Object getObject(int oneBasedIndex) {
            return rows.get(index)[oneBasedIndex - 1];
        }

        @Override
        public String getString(int oneBasedIndex) {
            Object value = getObject(oneBasedIndex);
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeSourceFactory implements EtlSourceFactory {
        private final EtlSource source;
        private int opened;

        FakeSourceFactory(EtlSource source) {
            this.source = source;
        }

        @Override
        public EtlSource open(DatabaseConfig config, EtlConnectionProvider connections) {
            opened++;
            return source;
        }
    }

    private static final class CountingCheckpoints implements EtlCheckpointStore {
        private final Map<Long, EtlCheckpointValue> values = new HashMap<>();
        private int saves;

        @Override
        public EtlCheckpointValue load(Long taskId) {
            return values.get(taskId);
        }

        @Override
        public void save(Long taskId, int jdbcType, String value) {
            saves++;
            values.put(taskId, new EtlCheckpointValue(jdbcType, value));
        }

        @Override
        public void clear(Long taskId) {
            values.remove(taskId);
        }
    }
}
