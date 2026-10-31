package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * The precheck's source half is driver-agnostic: it folds whatever {@link EtlSourceProbe}
 * returned into checks and columns, then compares the columns with the target table. The Mongo
 * source's own probing is pinned in {@code MongoEtlSourceTest}; here the probe is fed directly
 * so the page-level behaviour can be asserted without a server.
 */
class EtlPrecheckServiceMongoTest {

    private static final String TARGET_URL =
            "jdbc:h2:mem:precheck-mongo-dst-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    private static final List<EtlSourceColumn> COLUMNS = List.of(
            new EtlSourceColumn("id", Types.INTEGER, "Int32", null, null, true),
            new EtlSourceColumn("name", Types.CLOB, "String", null, null, true));

    private final DatabaseConfigService configService = mock(DatabaseConfigService.class);
    private final EtlConnectionProvider connections =
            config -> DriverManager.getConnection(TARGET_URL, "sa", "");
    private StubSource source;
    private EtlPrecheckService service;

    @BeforeEach
    void setUp() throws SQLException {
        reset();
        source = new StubSource();
        service = new EtlPrecheckService(configService, mock(DataSourceManager.class),
                (config, provider) -> source);
    }

    @Test
    void aMissingCollectionIsReportedAsAnError() {
        source.probe = new EtlSourceProbe(List.of(), -1, List.of(
                error("etl.precheck.mongo.collection.missing", "orders")));

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isFalse();
        assertThat(codes(result)).contains("etl.precheck.mongo.collection.missing");
    }

    @Test
    void aSuccessfulSampleListsTheColumnsAndProbesTheTarget() throws SQLException {
        execute("CREATE TABLE dst (id INT, name CLOB)");
        source.probe = new EtlSourceProbe(COLUMNS, -1, List.of(
                ok("etl.precheck.mongo.collection.ok", "orders"),
                ok("etl.precheck.mongo.filter.ok", null),
                ok("etl.precheck.mongo.sample.ok", "1")));

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isTrue();
        assertThat(result.columns()).extracting(EtlPrecheckResult.ColumnInfo::name)
                .containsExactly("id", "name");
        assertThat(codes(result)).contains(
                "etl.precheck.mongo.collection.ok", "etl.precheck.target.ok");
        assertThat(source.seenTimeout).isEqualTo(15);
    }

    @Test
    void anEmptySampleIsReportedAsAnError() {
        source.probe = new EtlSourceProbe(List.of(), -1, List.of(
                error("etl.precheck.mongo.sample.empty", "orders")));

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isFalse();
        assertThat(codes(result)).contains("etl.precheck.mongo.sample.empty");
    }

    @Test
    void aBlockedFilterIsReportedAsAnError() {
        EtlTask task = task();
        task.setSourceFilter("{\"$where\":\"true\"}");
        source.probe = new EtlSourceProbe(List.of(), -1, List.of(
                error("etl.precheck.mongo.filter.blocked", "error.etl.mongo.filter.blocked")));

        EtlPrecheckResult result = check(task);

        assertThat(result.passed()).isFalse();
        assertThat(codes(result)).contains("etl.precheck.mongo.filter.blocked");
    }

    @Test
    void anInvalidFieldIsReportedWithTheOffendingName() {
        source.probe = new EtlSourceProbe(List.of(), -1, List.of(
                error("etl.precheck.mongo.field.invalid", "bad-name")));

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isFalse();
        assertThat(result.checks())
                .filteredOn(check -> "etl.precheck.mongo.field.invalid".equals(check.code()))
                .extracting(EtlPrecheckResult.Check::detail)
                .containsExactly("bad-name");
    }

    @Test
    void aMissingResumeKeyIsReportedAsAnError() {
        EtlTask task = task();
        task.setResumeKeyColumn("seq");
        source.probe = new EtlSourceProbe(COLUMNS, -1, List.of(
                error("etl.precheck.mongo.resume.error", "seq")));

        EtlPrecheckResult result = check(task);

        assertThat(result.passed()).isFalse();
        assertThat(codes(result)).contains("etl.precheck.mongo.resume.error");
    }

    @Test
    void theTargetMustCoverEveryProbedColumn() throws SQLException {
        execute("CREATE TABLE dst (id INT)");
        source.probe = new EtlSourceProbe(COLUMNS, -1, List.of(
                ok("etl.precheck.mongo.sample.ok", "1")));

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isFalse();
        assertThat(result.checks())
                .filteredOn(check -> "etl.precheck.target.columns.error".equals(check.code()))
                .extracting(EtlPrecheckResult.Check::detail)
                .containsExactly("name");
    }

    @Test
    void aDriverFailureIsReportedUnderTheMongoConnectionCode() {
        source.failure = new EtlSourceException(
                "etl.precheck.mongo.connection.error", "connection refused");

        EtlPrecheckResult result = check(task());

        assertThat(result.passed()).isFalse();
        assertThat(result.checks())
                .filteredOn(check -> "etl.precheck.mongo.connection.error".equals(check.code()))
                .extracting(EtlPrecheckResult.Check::detail)
                .containsExactly("connection refused");
        assertThat(source.closed).isTrue();
    }

    // --- helpers ------------------------------------------------------------------------

    private EtlPrecheckResult check(EtlTask task) {
        return service.check(task, mongoConfig(), h2Config(), connections);
    }

    private static EtlTask task() {
        EtlTask task = new EtlTask();
        task.setId(7L);
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceCollection("orders");
        task.setSourceSql("");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static DatabaseConfig mongoConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static DatabaseConfig h2Config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(2L);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static List<String> codes(EtlPrecheckResult result) {
        List<String> codes = new ArrayList<>();
        for (EtlPrecheckResult.Check check : result.checks()) {
            codes.add(check.code());
        }
        return codes;
    }

    private static EtlSourceIssue ok(String code, String detail) {
        return new EtlSourceIssue(EtlPrecheckResult.Level.OK, code, detail);
    }

    private static EtlSourceIssue error(String code, String detail) {
        return new EtlSourceIssue(EtlPrecheckResult.Level.ERROR, code, detail);
    }

    private static void reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static final class StubSource implements EtlSource {
        private EtlSourceProbe probe = new EtlSourceProbe(List.of(), -1, List.of());
        private RuntimeException failure;
        private int seenTimeout;
        private boolean closed;

        @Override
        public EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) {
            throw new UnsupportedOperationException("not used by the precheck");
        }

        @Override
        public EtlSourceProbe probe(EtlTask task, int timeoutSeconds) {
            seenTimeout = timeoutSeconds;
            if (failure != null) {
                throw failure;
            }
            return probe;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
