package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Table-internal parallelism of the chunked initial load: disjoint key ranges copied by
 * independent workers must deliver exactly the same rows as the sequential load, completed
 * ranges must be skipped on resume, and every table shape that cannot be range-split must
 * quietly keep the sequential behavior.
 */
class DataSyncServiceParallelLoadTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");
    private static final String SRC_URL = "jdbc:h2:mem:par_src;DB_CLOSE_DELAY=-1";
    private static final String TGT_URL = "jdbc:h2:mem:par_tgt;DB_CLOSE_DELAY=-1";
    private static final String TGT2_URL = "jdbc:h2:mem:par_tgt2;DB_CLOSE_DELAY=-1";

    private Connection source;
    private Connection target;
    private Connection target2;
    private ChunkCheckpointRepository checkpoints;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private SyncContext ctx;
    private final AtomicInteger connectionOpens = new AtomicInteger();

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(SRC_URL, "sa", "");
        target = DriverManager.getConnection(TGT_URL, "sa", "");
        target2 = DriverManager.getConnection(TGT2_URL, "sa", "");
        checkpoints = mock(ChunkCheckpointRepository.class);

        sourceCfg = new DatabaseConfig();
        sourceCfg.setId(1L);
        targetCfg = new DatabaseConfig();
        targetCfg.setId(2L);
        Project project = new Project();
        project.setId(7L);
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .connectionProvider(cfg -> {
                    connectionOpens.incrementAndGet();
                    return DriverManager.getConnection(
                            cfg == sourceCfg ? SRC_URL : TGT_URL, "sa", "");
                })
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        exec(target2, "DROP ALL OBJECTS");
        source.close();
        target.close();
        target2.close();
    }

    @Test
    void parallelWorkersCoverEveryRowExactlyOnce() throws SQLException {
        createItems(10);
        DataSyncService service = service(3, 2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        assertThat(result.getRowsWritten()).isEqualTo(10);
        assertThat(result.getNewCursorValue()).isEqualTo("10");
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void completedAndPartialRangesResumeWhereTheyStopped() throws SQLException {
        createItems(10);
        // Crash leftovers for the 3-way split (0,4] (4,8] (8,10]: range 0 finished, range 1
        // committed one chunk covering ids 5-6. Rows 1-6 are already on the target.
        exec(target, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d'),"
                + " (5,'e'), (6,'f')");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "ITEMS", 0, 2, null, 0,
                        ChunkCheckpoint.STATUS_RANGE_DONE, 0L, 4L),
                new ChunkCheckpoint(7L, "ITEMS", 1, 1, "[6]", 2,
                        ChunkCheckpoint.STATUS_CHUNK, 4L, 8L)));
        DataSyncService service = service(3, 2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Only the tail is re-read: 7-8 finishing range 1, 9-10 filling range 2.
        assertThat(result.getRowsWritten()).isEqualTo(4);
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void changedParallelismDiscardsStaleRangeCheckpoints() throws SQLException {
        createItems(10);
        // Checkpoints from a 3-way split (0,4] (4,8] (8,10]. This run uses 2 workers, whose
        // ranges are (0,5] (5,10]: range 0's old RANGE_DONE would skip ids 5 entirely, and
        // range 1's old boundary [8] would skip 6-8. Both must be discarded, not trusted.
        exec(target, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d'),"
                + " (5,'e'), (6,'f'), (7,'g'), (8,'h')");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "ITEMS", 0, 2, null, 0,
                        ChunkCheckpoint.STATUS_RANGE_DONE, 0L, 4L),
                new ChunkCheckpoint(7L, "ITEMS", 1, 1, "[8]", 4,
                        ChunkCheckpoint.STATUS_CHUNK, 4L, 8L)));
        DataSyncService service = service(2, 2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Stale checkpoints mean a full re-read; upserts keep the already-written rows.
        assertThat(result.getRowsWritten()).isEqualTo(10);
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        // Once for the discard, once when the reload completes.
        verify(checkpoints, atLeastOnce()).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void shiftedKeySpanDiscardsStaleRangeCheckpoints() throws SQLException {
        createItems(10);
        // Same 2-way split, but recorded when the span ended at a different upper bound:
        // trusting (0,4] as if it were (0,5] would leave id 5 unread.
        exec(target, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d')");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "ITEMS", 0, 1, null, 0,
                        ChunkCheckpoint.STATUS_RANGE_DONE, 0L, 4L)));
        DataSyncService service = service(2, 2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isEqualTo(10);
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        verify(checkpoints, atLeastOnce()).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void sequentialLoadDiscardsRangeZeroCheckpointCarryingParallelBounds() throws SQLException {
        createItems(10);
        // A parallel run's range 0 is a slice, not the whole table. The sequential path
        // must not resume from its boundary as if it covered the full key span.
        exec(target, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d'), (5,'e')");
        when(checkpoints.findByProjectIdAndTableName(7L, "ITEMS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "ITEMS", 0, 0, "[5]", 5,
                        ChunkCheckpoint.STATUS_CHUNK, 0L, 5L)));
        DataSyncService service = service(1, 2);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isEqualTo(10);
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        verify(checkpoints, atLeastOnce()).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void interruptingTheCoordinatorStopsEveryWorker() throws Exception {
        createItems(2000);
        // One row per chunk keeps both workers busy long enough to interrupt mid-load.
        DataSyncService service = service(2, 1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<DataSyncService.TableSyncResult> run = caller.submit(() ->
                    service.syncTable(source, target, itemsMeta(), IDENTITY,
                            new SyncProgress(), ctx));
            awaitAtLeast(() -> connectionOpens.get() >= 2 && countQuietly(target) > 0, 10_000);

            // The only stop mechanism in production (CdcConsumerManager) interrupts the
            // thread running the load.
            caller.shutdownNow();

            Throwable failure = catchThrowable(() -> run.get(30, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(ExecutionException.class);
            assertThat(failure.getCause()).isInstanceOf(StopRequestedException.class);

            // The core contract: once the call has returned, no worker is still writing.
            long settled = count(target, "ITEMS");
            Thread.sleep(300);
            assertThat(count(target, "ITEMS")).isEqualTo(settled);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void parallelAndSequentialLoadsDeliverIdenticalRowSets() throws SQLException {
        createItems(20);

        service(4, 3).syncTable(source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        // Second run, sequential, into a different target database.
        DatabaseConfig targetCfg2 = new DatabaseConfig();
        targetCfg2.setId(3L);
        SyncContext ctx2 = ctx.toBuilder()
                .targetConfig(targetCfg2)
                .connectionProvider(cfg -> DriverManager.getConnection(
                        cfg == sourceCfg ? SRC_URL : cfg == targetCfg ? TGT_URL : TGT2_URL,
                        "sa", ""))
                .build();
        exec(target2, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        DataSyncService.TableSyncResult sequential = service(1, 3).syncTable(
                source, target2, itemsMeta(), IDENTITY, new SyncProgress(), ctx2);

        assertThat(sequential.isSuccess()).as(sequential.getError()).isTrue();
        assertThat(rows(target, "ITEMS")).isNotEmpty()
                .isEqualTo(rows(target2, "ITEMS"));
    }

    @Test
    void aCompositeKeyWhoseLeadingColumnIsIntegralLoadsInParallel() throws SQLException {
        exec(source, "CREATE TABLE LEDGER (ID BIGINT, TENANT VARCHAR(10), NAME VARCHAR(50),"
                + " PRIMARY KEY (ID, TENANT))");
        exec(target, "CREATE TABLE LEDGER (ID BIGINT, TENANT VARCHAR(10), NAME VARCHAR(50),"
                + " PRIMARY KEY (ID, TENANT))");
        exec(source, "INSERT INTO LEDGER VALUES (1,'a','x'), (2,'a','y'), (3,'a','z'),"
                + " (4,'b','p'), (5,'b','q'), (6,'b','r')");

        DataSyncService.TableSyncResult result = service(4, 2).syncTable(
                source, target, ledgerMeta(Types.BIGINT, Types.VARCHAR), IDENTITY,
                new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "LEDGER")).isEqualTo(6);
        // Ranges split on the leading column; a parallel load numbers them 0..n-1.
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
    }

    @Test
    void compositeKeyChunksDoNotSkipRowsSharingTheLeadingKeyValue() throws SQLException {
        // The leading column alone is not a key: several tenants share an id. A chunk
        // boundary recorded as "id = 1" would resume at "id > 1" and drop (1,'c') forever.
        exec(source, "CREATE TABLE LEDGER (ID BIGINT, TENANT VARCHAR(10), NAME VARCHAR(50),"
                + " PRIMARY KEY (ID, TENANT))");
        exec(target, "CREATE TABLE LEDGER (ID BIGINT, TENANT VARCHAR(10), NAME VARCHAR(50),"
                + " PRIMARY KEY (ID, TENANT))");
        exec(source, "INSERT INTO LEDGER VALUES (1,'a','x'), (1,'b','y'), (1,'c','z'),"
                + " (2,'a','p'), (2,'b','q'), (3,'a','r')");

        DataSyncService.TableSyncResult result = service(2, 2).syncTable(
                source, target, ledgerMeta(Types.BIGINT, Types.VARCHAR), IDENTITY,
                new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "LEDGER")).isEqualTo(6);
        // Each row belongs to exactly one range. An OR-chain bound combined with the upper
        // edge without parentheses would let a worker read past its slice (and the row to be
        // counted twice), even though upserts mask it in the final table.
        assertThat(result.getRowsWritten()).isEqualTo(6);
    }

    @Test
    void aTextPrimaryKeyLoadsInParallelThroughSampledBoundaries() throws SQLException {
        exec(source, "CREATE TABLE ITEMS (ID VARCHAR(20) PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID VARCHAR(20) PRIMARY KEY, NAME VARCHAR(50))");
        StringBuilder insert = new StringBuilder("INSERT INTO ITEMS VALUES ");
        for (int i = 1; i <= 10; i++) {
            insert.append(i > 1 ? ", " : "").append("('k").append(i).append("', 'v").append(i)
                    .append("')");
        }
        exec(source, insert.toString());

        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.VARCHAR);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));

        DataSyncService.TableSyncResult result = service(2, 2).syncTable(
                source, target, table, CursorStrategy.none("full load only"),
                new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "ITEMS")).isEqualTo(10);
        assertThat(result.getRowsWritten()).isEqualTo(10);
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
        // Sampled slices carry string bounds, which the long range columns cannot hold.
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeUpperKey() != null);
    }

    @Test
    void aCompositeKeyWithATextLeadingColumnLoadsInParallelThroughSampling() throws SQLException {
        exec(source, "CREATE TABLE LEDGER (TENANT VARCHAR(10), ID BIGINT, NAME VARCHAR(50),"
                + " PRIMARY KEY (TENANT, ID))");
        exec(target, "CREATE TABLE LEDGER (TENANT VARCHAR(10), ID BIGINT, NAME VARCHAR(50),"
                + " PRIMARY KEY (TENANT, ID))");
        exec(source, "INSERT INTO LEDGER VALUES ('a',1,'x'), ('a',2,'y'), ('a',3,'z'),"
                + " ('b',1,'p'), ('b',2,'q'), ('b',3,'r')");

        ColumnMeta tenant = new ColumnMeta();
        tenant.setName("TENANT");
        tenant.setJdbcType(Types.VARCHAR);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        TableMeta table = new TableMeta();
        table.setName("LEDGER");
        table.setPrimaryKeys(List.of("TENANT", "ID"));
        table.setColumns(List.of(tenant, id));

        DataSyncService.TableSyncResult result = service(4, 2).syncTable(
                source, target, table, IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "LEDGER")).isEqualTo(6);
        assertThat(result.getRowsWritten()).isEqualTo(6);
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
        // A text-led key's slice bounds cannot live in the numeric columns.
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeUpperKey() != null);
    }

    @Test
    void aZeroScaleNumberKeyIsRangeSplitLikeAnInteger() throws SQLException {
        // A real Oracle NUMBER(18) primary key reports as NUMERIC with scale 0 — an exact
        // integer domain. Without the gate accepting it, Oracle sources could never use
        // the parallel path at all (task book M5).
        exec(source, "CREATE TABLE ITEMS (ID NUMERIC(18) PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID NUMERIC(18) PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1,'a'), (2,'b'), (3,'c'), (4,'d'), (5,'e')");

        DataSyncService.TableSyncResult result = service(2, 2).syncTable(
                source, target, numericItemsMeta(18, 0), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "ITEMS")).isEqualTo(5);
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
    }

    @Test
    void aFractionalNumberKeyIsSplitOnExactSampledBoundaries() throws SQLException {
        // Scale > 0 forbids the arithmetic long split, but sampled boundaries keep the key's
        // exact type — no truncation — so the ranges stay disjoint and the parallel model
        // applies. Rows must be delivered exactly once.
        exec(source, "CREATE TABLE ITEMS (ID NUMERIC(10,2) PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID NUMERIC(10,2) PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1.5,'a'), (2.5,'b'), (3.5,'c'), (4.5,'d')");

        DataSyncService.TableSyncResult result = service(4, 2).syncTable(
                source, target, numericItemsMeta(10, 2), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "ITEMS")).isEqualTo(4);
        assertThat(result.getRowsWritten()).isEqualTo(4);
        ArgumentCaptor<ChunkCheckpoint> captor = ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(c -> c.getRangeIndex() != null && c.getRangeIndex() > 0);
    }

    @Test
    void withoutAConnectionProviderTheLoadDegradesToSequential() throws SQLException {
        createItems(5);
        SyncContext noProvider = ctx.toBuilder().connectionProvider(null).build();

        DataSyncService.TableSyncResult result = service(4, 2).syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), noProvider);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count(target, "ITEMS")).isEqualTo(5);
        verify(checkpoints, atLeastOnce()).save(any(ChunkCheckpoint.class));
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void anEmptyTableFinalizesCleanlyOnTheParallelPath() throws SQLException {
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        DataSyncService.TableSyncResult result = service(3, 2).syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isZero();
        assertThat(result.getNewCursorValue()).isNull();
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "ITEMS");
    }

    @Test
    void rangeSplittingIsContiguousAndOverflowSafe() {
        assertThat(DataSyncService.splitRanges(1, 10, 3))
                .containsExactly(new long[] {0, 4}, new long[] {4, 8}, new long[] {8, 10});
        assertThat(DataSyncService.splitRanges(5, 5, 4))
                .containsExactly(new long[] {4, 5});
        assertThat(DataSyncService.splitRanges(1, 10, 1))
                .containsExactly(new long[] {0, 10});
        // Spans too wide for safe arithmetic fall back to sequential instead of splitting wrong.
        assertThat(DataSyncService.splitRanges(Long.MIN_VALUE, Long.MAX_VALUE, 4)).isNull();
        assertThat(DataSyncService.splitRanges(0, Long.MAX_VALUE, 8)).isNull();
        // Sparse wide span: still contiguous, first lo = min-1, last hi = max.
        List<long[]> sparse = DataSyncService.splitRanges(1_000_000L, 900_000_000L, 8);
        assertThat(sparse).isNotNull();
        assertThat(sparse.get(0)[0]).isEqualTo(999_999L);
        assertThat(sparse.get(sparse.size() - 1)[1]).isEqualTo(900_000_000L);
        for (int i = 1; i < sparse.size(); i++) {
            assertThat(sparse.get(i)[0]).isEqualTo(sparse.get(i - 1)[1]);
        }
    }

    private static void awaitAtLeast(java.util.function.BooleanSupplier condition,
                                     long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(10);
        }
    }

    private long countQuietly(Connection conn) {
        try {
            return count(conn, "ITEMS");
        } catch (SQLException e) {
            return -1;
        }
    }

    private DataSyncService service(int parallelism, int chunkSize) {
        SyncProperties properties = new SyncProperties();
        properties.setFullLoadParallelism(parallelism);
        properties.setChunkSize(chunkSize);
        return new DataSyncService(properties, checkpoints);
    }

    private void createItems(int rows) throws SQLException {
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        StringBuilder insert = new StringBuilder("INSERT INTO ITEMS VALUES ");
        for (int i = 1; i <= rows; i++) {
            insert.append(i > 1 ? ", " : "").append('(').append(i).append(", '")
                    .append((char) ('a' + (i - 1) % 26)).append("')");
        }
        exec(source, insert.toString());
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

    /** ITEMS whose single-column PK is NUMERIC(size, digits) — what Oracle NUMBER reports. */
    private static TableMeta numericItemsMeta(int size, int digits) {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.NUMERIC);
        id.setSize(size);
        id.setDecimalDigits(digits);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));
        return table;
    }

    /** LEDGER metadata with a two-column PK {@code (ID, TENANT)}. */
    private static TableMeta ledgerMeta(int leadingType, int trailingType) {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(leadingType);
        ColumnMeta tenant = new ColumnMeta();
        tenant.setName("TENANT");
        tenant.setJdbcType(trailingType);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        TableMeta table = new TableMeta();
        table.setName("LEDGER");
        table.setPrimaryKeys(List.of("ID", "TENANT"));
        table.setColumns(List.of(id, tenant, name));
        return table;
    }

    private long count(Connection conn, String tableName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private List<String> rows(Connection conn, String tableName) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ID, NAME FROM " + tableName + " ORDER BY ID")) {
            while (rs.next()) {
                rows.add(rs.getLong(1) + ":" + rs.getString(2));
            }
        }
        return rows;
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
