package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
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
import com.qqmu.jync.service.etl.mongo.MongoResumeKeyCodec;

/**
 * The text the runner stores as the resume key is what the source reads back on the next run.
 *
 * <p>For a JDBC source that text is the driver's own rendering, which only needs rewriting when
 * the binder cannot parse it (Oracle's NLS formats). For MongoDB the cursor's text is already
 * {@link MongoResumeKeyCodec}'s canonical encoding — an ISO-8601 instant for a date — and the
 * JDBC rewrite must not touch it: {@code MongoResumeKeyCodec.decode} parses the stored text with
 * {@code Instant.parse}, so a rewritten wall clock makes every continuation fail as
 * "error.etl.mongo.resume.invalid".
 */
class EtlRunnerResumeKeyTextTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:etl-resume-text-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

    private static final List<EtlSourceColumn> MONGO_COLUMNS = List.of(
            new EtlSourceColumn("id", Types.INTEGER, "Int32", null, null, true),
            new EtlSourceColumn("updatedAt", Types.TIMESTAMP, "Date", null, null, true));
    private static final List<EtlSourceColumn> JDBC_COLUMNS = List.of(
            new EtlSourceColumn("id", Types.INTEGER, "NUMBER", null, null, true),
            new EtlSourceColumn("updatedAt", Types.TIMESTAMP, "TIMESTAMP", null, null, true));

    private final MapCheckpoints checkpoints = new MapCheckpoints();

    @BeforeEach
    void resetTarget() throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             var s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void aMongoDateResumeKeyKeepsTheCodecTextSoTheContinuationCanDecodeIt() {
        Instant instant = Instant.parse("2026-11-14T02:00:00Z");
        FakeMongoSource source = new FakeMongoSource(MONGO_COLUMNS,
                new Object[]{1, new Timestamp(instant.toEpochMilli())}, instant.toString());
        EtlRunResult result = run(source, mongoConfig(1L), task("updatedAt"));

        assertThat(result.success()).isTrue();
        EtlCheckpointValue saved = checkpoints.load(7L);
        assertThat(saved).isNotNull();
        assertThat(saved.jdbcType()).isEqualTo(Types.TIMESTAMP);
        assertThat(saved.value()).isEqualTo("2026-11-14T02:00:00Z");
        // The continuation decodes the stored text with Instant.parse; that is the contract
        // the checkpoint must keep for a native Mongo source.
        assertThat(MongoResumeKeyCodec.decode(saved.value(), saved.jdbcType()))
                .isEqualTo(Date.from(instant));
    }

    @Test
    void anOracleNlsResumeKeyIsStillRewrittenAsAWallClock() {
        FakeJdbcSource source = new FakeJdbcSource(JDBC_COLUMNS,
                new Object[]{1, LocalDateTime.of(2026, 11, 14, 10, 0)},
                "14-NOV-26 10.00.00.000000 AM");
        EtlRunResult result = run(source, oracleConfig(1L), task("updatedAt"));

        assertThat(result.success()).isTrue();
        EtlCheckpointValue saved = checkpoints.load(7L);
        assertThat(saved).isNotNull();
        assertThat(saved.value()).isEqualTo("2026-11-14 10:00:00");
    }

    @Test
    void anExtremeTimeResumeKeyIsRefusedBeforeItCanPoisonTheCheckpoint() {
        // MySQL TIME runs to 838:59:59; the cursor keeps that literal (no JDBC Time can carry
        // it), and no later run could bind it. Failing here names the column instead of
        // letting every future run die with "stored resume value ... cannot be read back".
        List<EtlSourceColumn> columns = List.of(
                new EtlSourceColumn("id", Types.INTEGER, "INT", null, null, true),
                new EtlSourceColumn("updatedAt", Types.TIME, "TIME", null, null, true));

        EtlRunResult result = run(
                new FakeJdbcSource(columns, new Object[]{1, "838:59:59"}, "838:59:59"),
                h2Config(1L), task("updatedAt"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(result.errorMessage())
                .isEqualTo("error.etl.resumeKey.unbindable:updatedAt");
        assertThat(checkpoints.load(7L)).isNull();
    }

    @Test
    void aBinaryResumeKeyIsRefusedInsteadOfStoredAsLossyText() {
        // getString decodes raw bytes with the connection charset; setString re-encodes
        // them. The continuation would compare different bytes and skip or duplicate rows.
        List<EtlSourceColumn> columns = List.of(
                new EtlSourceColumn("id", Types.INTEGER, "INT", null, null, true),
                new EtlSourceColumn("updatedAt", Types.VARBINARY, "VARBINARY", null, null, true));

        EtlRunResult result = run(
                new FakeJdbcSource(columns, new Object[]{1, new byte[]{1, 2}}, "ab"),
                h2Config(1L), task("updatedAt"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("error.etl.resumeKey.unbindable:updatedAt");
        assertThat(checkpoints.load(7L)).isNull();
    }

    // --- harness ------------------------------------------------------------------------

    private EtlRunResult run(EtlSource source, DatabaseConfig sourceConfig, EtlTask task) {
        EtlRunner runner = new EtlRunner(
                new SqlDialectFactory(List.of(), new GenericSqlDialect()), checkpoints,
                (config, connections) -> source);
        return runner.run(task, sourceConfig, h2Config(2L),
                config -> DriverManager.getConnection(TARGET_URL, "sa", ""));
    }

    private static EtlTask task(String resumeKey) {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceCollection("orders");
        task.setSourceSql("SELECT * FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setResumeKeyColumn(resumeKey);
        return task;
    }

    private static DatabaseConfig mongoConfig(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static DatabaseConfig oracleConfig(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.ORACLE);
        return config;
    }

    private static DatabaseConfig h2Config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.H2);
        return config;
    }

    private abstract static class SingleRowSource implements EtlSource {
        final List<EtlSourceColumn> columns;
        final Object[] row;
        final String keyText;

        SingleRowSource(List<EtlSourceColumn> columns, Object[] row, String keyText) {
            this.columns = columns;
            this.row = row;
            this.keyText = keyText;
        }

        @Override
        public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
            throw new UnsupportedOperationException("not used by the runner");
        }

        @Override
        public void close() {
        }
    }

    /** Mimics {@code MongoEtlSourceCursor}: values rendered as JDBC types, key text encoded. */
    private static final class FakeMongoSource extends SingleRowSource {
        FakeMongoSource(List<EtlSourceColumn> columns, Object[] row, String keyText) {
            super(columns, row, keyText);
        }

        @Override
        public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
            return new SingleRowCursor(columns, row, keyText);
        }
    }

    private static final class FakeJdbcSource extends SingleRowSource {
        FakeJdbcSource(List<EtlSourceColumn> columns, Object[] row, String keyText) {
            super(columns, row, keyText);
        }

        @Override
        public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
            return new SingleRowCursor(columns, row, keyText);
        }
    }

    private static final class SingleRowCursor implements EtlSourceCursor {
        private final List<EtlSourceColumn> columns;
        private final Object[] row;
        private final String keyText;
        private boolean consumed;

        SingleRowCursor(List<EtlSourceColumn> columns, Object[] row, String keyText) {
            this.columns = columns;
            this.row = row;
            this.keyText = keyText;
        }

        @Override
        public List<EtlSourceColumn> columns() {
            return columns;
        }

        @Override
        public int resumeKeyIndex() {
            for (int i = 0; i < columns.size(); i++) {
                if ("updatedAt".equals(columns.get(i).name())) {
                    return i + 1;
                }
            }
            return -1;
        }

        @Override
        public boolean next() {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }

        @Override
        public Object getObject(int oneBasedIndex) {
            return row[oneBasedIndex - 1];
        }

        @Override
        public String getString(int oneBasedIndex) {
            return oneBasedIndex == resumeKeyIndex() ? keyText : String.valueOf(getObject(oneBasedIndex));
        }

        @Override
        public void close() {
        }
    }

    private static final class MapCheckpoints implements EtlCheckpointStore {
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
