package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.cdc.LogCapture;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The chunked initial full load, end to end over H2: every row must arrive exactly as with the
 * classic streamed load, checkpoints must describe only an in-flight load, and a stored
 * boundary must make a restarted load skip the chunks it already committed.
 */
class DataSyncServiceChunkedLoadTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private ChunkCheckpointRepository checkpoints;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:chunk_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:chunk_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        checkpoints = mock(ChunkCheckpointRepository.class);

        Project project = new Project();
        project.setId(7L);
        ctx = SyncContext.builder()
                .project(project)
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
    void aKeyedInitialLoadIsCopiedInChunksAndClearsItsCheckpoints() throws SQLException {
        createItems(5);
        DataSyncService service = service(2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.isInitialLoad()).isTrue();
        assertThat(count("ITEMS")).isEqualTo(5);
        assertThat(result.getRowsWritten()).isEqualTo(5);
        // Cursor semantics are unchanged: identity watermark stored as-is.
        assertThat(result.getNewCursorValue()).isEqualTo("5");
        // The chunked path ran, so the UI may promise checkpointed resume for this table.
        assertThat(result.getResumableLoad()).isTrue();
        // Chunks of 2+2+1: one checkpoint per completed chunk, all cleared at the end.
        verify(checkpoints, times(3)).save(any(ChunkCheckpoint.class));
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void aStoredBoundaryResumesAfterTheLastCompletedChunk() throws SQLException {
        createItems(5);
        // Simulate the state a crash left behind: chunks 0 and 1 (ids 1-4) already committed
        // on the target, the newest checkpoint recording the boundary at id 4.
        exec(target, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d')");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS"))
                .thenReturn(List.of(new ChunkCheckpoint(7L, "ITEMS", 0, 1, "[4]", 2,
                        ChunkCheckpoint.STATUS_CHUNK)));
        DataSyncService service = service(2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Only the tail (id 5) is re-read; the completed chunks are skipped.
        assertThat(result.getRowsWritten()).isEqualTo(1);
        assertThat(count("ITEMS")).isEqualTo(5);
        // The resumed load ran one more chunk (index 2) and then cleared everything.
        verify(checkpoints, times(1)).save(any(ChunkCheckpoint.class));
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
        assertThat(result.getNewCursorValue()).isEqualTo("5");
    }

    @Test
    void aResumedLoadOfABinaryKeySeeksWithTheStoredBytes() throws SQLException {
        exec(source, "CREATE TABLE BIN_ITEMS (ID VARBINARY(16) PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE BIN_ITEMS (ID VARBINARY(16) PRIMARY KEY, NAME VARCHAR(50))");
        for (int i = 1; i <= 5; i++) {
            exec(source, "INSERT INTO BIN_ITEMS VALUES (X'" + String.format("%02X", i)
                    + "', 'n" + i + "')");
        }
        // A crash left the first chunk (keys 01, 02) committed on the target, its last key
        // recorded through the checkpoint's JSON boundary.
        exec(target, "INSERT INTO BIN_ITEMS VALUES (X'01','n1'), (X'02','n2')");
        when(checkpoints.findByProjectIdAndTableName(7L, "BIN_ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "BIN_ITEMS", 0, 0,
                        KeysetChunker.encodePk(List.of(new byte[] {2})), 2,
                        ChunkCheckpoint.STATUS_CHUNK)));

        DataSyncService.TableSyncResult result = service(2).syncTable(source, target,
                binItemsMeta(), CursorStrategy.none("full load only"), new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Only the tail is re-read, and every row is present exactly once.
        assertThat(count("BIN_ITEMS")).isEqualTo(5);
        assertThat(result.getRowsWritten()).isEqualTo(3);
    }

    /**
     * A full-compare table is re-read in full on every poll interval. When every row is
     * absorbed by an upsert that changed nothing, the throughput line must stay off INFO: at
     * that level an idle pass reads as data still moving, which is exactly what an operator
     * watching the log concludes.
     *
     * <p>A key-only table is the H2 case where an absorbed write really reports zero changes;
     * with a non-key column H2 counts the no-op MERGE/UPDATE as a write.
     */
    @Test
    void anUnchangedFullComparePassKeepsThroughputOffInfo() throws SQLException {
        exec(source, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 1200; i++) {
            values.append(i == 1 ? "" : ", ").append('(').append(i).append(')');
        }
        exec(source, "INSERT INTO KEYONLY VALUES " + values);

        TableMeta table = new TableMeta();
        table.setName("KEYONLY");
        table.setPrimaryKeys(List.of("ID"));
        DataSyncService service = service(2000);
        CursorStrategy fullCompare = CursorStrategy.fullCompare("no usable cursor");

        DataSyncService.TableSyncResult seed = service.syncTable(
                source, target, table, fullCompare, new SyncProgress(), ctx);
        assertThat(seed.getRowsChanged()).isEqualTo(1200);

        ListAppender<ILoggingEvent> logs = LogCapture.attach(ThroughputMeter.class);
        try {
            DataSyncService.TableSyncResult idle = service.syncTable(
                    source, target, table, fullCompare, new SyncProgress(), ctx);

            assertThat(idle.isSuccess()).as(idle.getError()).isTrue();
            assertThat(idle.getRowsWritten()).as("整表又被读了一遍").isEqualTo(1200);
            assertThat(idle.getRowsChanged()).as("目标一行都没动").isZero();
        } finally {
            LogCapture.detach(ThroughputMeter.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.INFO, logs))
                .as("an idle re-read must not look like data movement")
                .isEmpty();
    }

    @Test
    void aKeyedFullCompareSeedStoresTheRowCountFingerprintThroughTheChunkedPath()
            throws SQLException {
        createItems(5);
        DataSyncService service = service(2);
        CursorStrategy fullCompare = CursorStrategy.fullCompare("no usable cursor");

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), fullCompare, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count("ITEMS")).isEqualTo(5);
        assertThat(result.getNewCursorValue()).isEqualTo("fc:5");
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void aKeylessTableKeepsTheClassicStreamedLoadAndNeverTouchesCheckpoints() throws SQLException {
        exec(source, "CREATE TABLE LOGS (LINE VARCHAR(50))");
        exec(target, "CREATE TABLE LOGS (LINE VARCHAR(50))");
        exec(source, "INSERT INTO LOGS VALUES ('a'), ('b'), ('c'), ('d'), ('e')");
        DataSyncService service = service(2);

        TableMeta table = new TableMeta();
        table.setName("LOGS"); // deliberately no primary key

        DataSyncService.TableSyncResult result = service.syncTable(source, target, table,
                CursorStrategy.fullCompare("no usable cursor"), new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count("LOGS")).isEqualTo(5);
        verifyNoInteractions(checkpoints);
        // Task book A1: a PK-less table's streamed load cannot resume, and the UI must say so
        // explicitly instead of leaving the user to discover it after an interruption.
        assertThat(result.getResumableLoad()).isFalse();
    }

    @Test
    void chunkSizeZeroDisablesChunkingEntirely() throws SQLException {
        createItems(5);
        DataSyncService service = service(0);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count("ITEMS")).isEqualTo(5);
        assertThat(result.getNewCursorValue()).isEqualTo("5");
        verifyNoInteractions(checkpoints);
        // Chunking is a global switch here: with it off the resume question was never asked,
        // so no per-table claim is recorded either way.
        assertThat(result.getResumableLoad()).isNull();
    }

    /**
     * Final review FIX 2: the sequential chunk loop is the SHARED polling engine, but a
     * CDC disable/rebaseline/shutdown whose join budget expired INTERRUPTS the thread —
     * and the load must then abort at the next chunk boundary instead of writing for
     * hours after "stopped". Chunk 0 (ids 1-2) commits and checkpoints; the checkpoint
     * save flips the interrupt flag exactly like CdcConsumerManager.ensureStopped does;
     * the next boundary aborts before completion.
     */
    @Test
    void anInterruptedSequentialLoadAbortsAtTheNextChunkBoundary() throws SQLException {
        createItems(5);
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(checkpoints).save(any(ChunkCheckpoint.class));
        DataSyncService service = service(2);

        try {
            assertThatThrownBy(() -> service.syncTable(
                    source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx))
                    .isInstanceOf(StopRequestedException.class);

            // Aborted BEFORE completion: only chunk 0 reached the target, the checkpoints
            // were kept (the load resumes instead of reloading) and no done-cursor was
            // produced — the CDC baseline riding this engine therefore stays NOT done.
            assertThat(count("ITEMS")).isEqualTo(2);
            verify(checkpoints, times(1)).save(any(ChunkCheckpoint.class));
            verify(checkpoints, never()).deleteByProjectIdAndTableName(7L, "ITEMS");
            // The flag deliberately stays SET: CdcBaselineService/CdcConsumer read it to
            // classify the abort as stop-requested (no ERROR hat) — never cleared here.
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();  // clear, so later tests on this thread are unaffected
        }
    }

    @Test
    void theRowCountAuditBowsOutForAPseudoColumnCursor() throws SQLException {
        createItems(5);
        DataSyncService service = service(2);
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue("1234567");

        // ORA_ROWSCN exists only on the source; the target (H2 here, any real target in
        // production) has no such column to bound the comparison with.
        assertThat(service.auditSyncedRowCounts(source, target, itemsMeta(),
                CursorStrategy.rowScn("row-level SCN"), progress, ctx)).isNull();
    }

    /**
     * The throughput line is the one piece of evidence a customer is asked to send in when a
     * first full load is slow. It must say how much of the write share was commit wait: a
     * target whose commits cost seconds is fixed by fewer, larger commits, while slow row
     * work needs a different answer entirely — the summary cannot leave the two merged.
     */
    @Test
    void theThroughputLineCarvesCommitWaitOutOfTheWriteShare() throws SQLException {
        // At or above the meter's INFO threshold, so the line is actually emitted.
        createItems(1200);
        ListAppender<ILoggingEvent> logs = LogCapture.attach(ThroughputMeter.class);
        try {
            // A target whose COMMIT costs real time, like a redo-log-bound production one.
            DataSyncService.TableSyncResult result = service(2000).syncTable(
                    source, slowCommit(target), itemsMeta(), IDENTITY, new SyncProgress(), ctx);

            assertThat(result.isSuccess()).as(result.getError()).isTrue();
        } finally {
            LogCapture.detach(ThroughputMeter.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.INFO, logs))
                .as("commit waits must be booked into their own share of the throughput line")
                .anyMatch(line -> line.contains("commit=") && !line.contains("commit=0%"));
    }

    /**
     * Row mapping ({@code ResultSet} → map) belongs to the read share. It used to be charged
     * to the write share as well, because the write window started at the timestamp taken
     * before the mapping instead of after it: with an expensive mapping (wide tables, LOBs)
     * that halved the apparent read share and inflated write, understating the commit
     * remainder the customer is asked to read.
     */
    @Test
    void theThroughputLineDoesNotChargeRowMappingTimeToTheWriteShare() throws SQLException {
        createItems(1000);
        ListAppender<ILoggingEvent> logs = LogCapture.attach(ThroughputMeter.class);
        try {
            DataSyncService.TableSyncResult result = service(2000).syncTable(
                    slowMapping(source), target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);
            assertThat(result.isSuccess()).as(result.getError()).isTrue();
        } finally {
            LogCapture.detach(ThroughputMeter.class, logs);
        }

        String line = LogCapture.messagesAt(Level.INFO, logs).stream()
                .filter(l -> l.contains("read="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no throughput line: " + logs.list));
        int readPct = Integer.parseInt(line.replaceAll(".*read=(\\d+)%.*", "$1"));
        assertThat(readPct)
                .as("mapping is read work; the write share is cleanse+bind+addBatch only: %s", line)
                .isGreaterThanOrEqualTo(75);
    }

    /**
     * Delegates everything to the real source, but makes every fifth column read take 3ms —
     * the mapping cost of a wide or LOB-heavy table, without a wide table in the fixture.
     */
    private static Connection slowMapping(Connection real) {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DataSyncServiceChunkedLoadTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof java.sql.PreparedStatement ps) {
                        return slowStatement(ps, calls, java.sql.PreparedStatement.class);
                    }
                    if (result instanceof Statement statement) {
                        return slowStatement(statement, calls, Statement.class);
                    }
                    return result;
                });
    }

    private static Statement slowStatement(Statement real,
                                           java.util.concurrent.atomic.AtomicInteger calls,
                                           Class<?> iface) {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                DataSyncServiceChunkedLoadTest.class.getClassLoader(),
                new Class<?>[] {iface},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof ResultSet rs && "executeQuery".equals(method.getName())) {
                        return slowResultSet(rs, calls);
                    }
                    return result;
                });
    }

    private static ResultSet slowResultSet(ResultSet real,
                                           java.util.concurrent.atomic.AtomicInteger calls) {
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                DataSyncServiceChunkedLoadTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if (("getObject".equals(method.getName())
                            || "getString".equals(method.getName()))
                            && calls.incrementAndGet() % 5 == 0) {
                        try {
                            Thread.sleep(3L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Delegates everything to the real target, but makes every COMMIT take {@code millis}. */
    private static Connection slowCommit(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DataSyncServiceChunkedLoadTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName()) && (args == null || args.length == 0)) {
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private DataSyncService service(int chunkSize) {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(chunkSize);
        return new DataSyncService(properties, checkpoints);
    }

    private void createItems(int rows) throws SQLException {
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        for (int i = 1; i <= rows; i++) {
            exec(source, "INSERT INTO ITEMS VALUES (" + i + ", '" + (char) ('a' + i - 1) + "')");
        }
    }

    private TableMeta itemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);

        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));
        return table;
    }

    /** Same shape as {@link #itemsMeta()}, with a binary key the JSON boundary cannot hold. */
    private TableMeta binItemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.VARBINARY);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);

        TableMeta table = new TableMeta();
        table.setName("BIN_ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));
        return table;
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
