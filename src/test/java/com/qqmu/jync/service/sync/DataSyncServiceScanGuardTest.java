package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The two bookkeeping {@code COUNT(*)}s — the NULL-cursor probe and the row-count audit —
 * scan the entire table when the cursor column has no index. On a large source that is a
 * statement designed to hit the timeout, every cycle, for a number the operator does not
 * need. Both are skipped past a size ceiling, with one log line saying why.
 */
class DataSyncServiceScanGuardTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");
    private static final long LARGE = 5_000_000L;

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:guard_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:guard_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void theAuditIsSkippedWhenTheCursorColumnIsUnindexedAndTheTableIsLarge() throws SQLException {
        exec(source, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO BIG VALUES (1, 'a')");
        TableMeta table = table("BIG", false);
        table.setApproximateRowCount(LARGE);

        List<String> executed = new CopyOnWriteArrayList<>();
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertThat(service.auditSyncedRowCounts(recording(source, executed), target, table,
                IDENTITY, progress("1"), ctx)).isNull();
        assertThat(countQueries(executed)).isEmpty();
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("BIG").contains("max-unindexed-scan-rows"));
    }

    @Test
    void theAuditRunsWhenTheCursorColumnIsIndexed() throws SQLException {
        exec(source, "CREATE TABLE BIG (ID BIGINT PRIMARY KEY, V VARCHAR(10))");
        exec(target, "CREATE TABLE BIG (ID BIGINT PRIMARY KEY, V VARCHAR(10))");
        exec(source, "INSERT INTO BIG VALUES (1, 'a')");
        TableMeta table = table("BIG", true);
        table.setApproximateRowCount(LARGE);

        List<String> executed = new CopyOnWriteArrayList<>();

        DataSyncService.RowCountAudit audit = service.auditSyncedRowCounts(
                recording(source, executed), target, table, IDENTITY, progress("1"), ctx);

        assertThat(audit).isNotNull();
        assertThat(countQueries(executed)).isNotEmpty();
    }

    @Test
    void theAuditRunsWhenTheTableIsBelowTheCeiling() throws SQLException {
        exec(source, "CREATE TABLE SMALL (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE SMALL (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO SMALL VALUES (1, 'a')");
        TableMeta table = table("SMALL", false);
        table.setApproximateRowCount(10L);

        assertThat(service.auditSyncedRowCounts(source, target, table, IDENTITY,
                progress("1"), ctx)).isNotNull();
    }

    @Test
    void theAuditRunsWhenTheSizeCannotBeEstablished() throws SQLException {
        exec(source, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO BIG VALUES (1, 'a')");

        // No estimate on the table and no reader to ask: an unknown size must not be read
        // as "small enough to skip" — that would silently disable the audit on real data.
        assertThat(service.auditSyncedRowCounts(source, target, table("BIG", false), IDENTITY,
                progress("1"), ctx)).isNotNull();
    }

    @Test
    void theAuditAsksTheReaderForASizeTheTableMetaDoesNotCarry() throws SQLException {
        exec(source, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE BIG (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO BIG VALUES (1, 'a')");
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.estimateRowCount(any(), any(), anyString())).thenReturn(LARGE);
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceReader(reader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();

        assertThat(service.auditSyncedRowCounts(source, target, table("BIG", false), IDENTITY,
                progress("1"), ctx)).isNull();
    }

    @Test
    void theNullCursorProbeIsSkippedOnALargeUnindexedTable() throws SQLException {
        exec(source, "CREATE TABLE EVENTS (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE EVENTS (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO EVENTS VALUES (1, 'a'), (2, 'b')");
        TableMeta table = table("EVENTS", false);
        table.setApproximateRowCount(LARGE);

        List<String> executed = new CopyOnWriteArrayList<>();
        service.syncTable(recording(source, executed), target, table, IDENTITY,
                progress("0"), ctx);

        assertThat(countQueries(executed))
                .as("the COUNT(*) WHERE cursor IS NULL would scan the whole table")
                .isEmpty();
    }

    @Test
    void theNullCursorProbeStillRunsOnASmallKeylessTable() throws SQLException {
        exec(source, "CREATE TABLE EVENTS (ID BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE EVENTS (ID BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO EVENTS VALUES (1, 'a'), (2, 'b')");
        TableMeta table = table("EVENTS", false);
        table.setApproximateRowCount(2L);

        List<String> executed = new CopyOnWriteArrayList<>();
        service.syncTable(recording(source, executed), target, table, IDENTITY,
                progress("0"), ctx);

        assertThat(countQueries(executed))
                .anySatisfy(sql -> assertThat(sql).contains("IS NULL"));
    }

    private static List<String> countQueries(List<String> executed) {
        return executed.stream()
                .filter(sql -> sql.startsWith("SELECT COUNT(*)"))
                .toList();
    }

    private static SyncProgress progress(String cursor) {
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(cursor);
        return progress;
    }

    private static TableMeta table(String name, boolean keyed) {
        TableMeta table = new TableMeta();
        table.setName(name);
        if (keyed) {
            table.setPrimaryKeys(List.of("ID"));
        }
        return table;
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) org.slf4j.LoggerFactory.getLogger(DataSyncService.class)).addAppender(appender);
        return appender;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Delegating connection that records the SQL each statement executes. */
    private static Connection recording(Connection delegate, List<String> executed) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String) {
                        return recordingStatement((PreparedStatement) result, PreparedStatement.class,
                                (String) args[0], executed);
                    }
                    if ("createStatement".equals(method.getName())) {
                        return recordingStatement((Statement) result, Statement.class, null,
                                executed);
                    }
                    return result;
                });
    }

    private static Object recordingStatement(Object delegate, Class<?> type, String preparedSql,
                                             List<String> executed) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    String sql = preparedSql;
                    if (sql == null && args != null && args.length == 1
                            && args[0] instanceof String) {
                        sql = (String) args[0];
                    }
                    if (sql != null && ("executeQuery".equals(method.getName())
                            || "executeUpdate".equals(method.getName())
                            || "execute".equals(method.getName()))) {
                        executed.add(sql);
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
