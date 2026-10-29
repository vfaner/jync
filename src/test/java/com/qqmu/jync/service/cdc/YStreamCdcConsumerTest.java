package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.YStreamEvent.Operation;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.service.cdc.YStreamServiceLifecycle.Outcome;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The capture loop's contract: a source transaction becomes exactly one target commit, the
 * position advances only after that commit, and anything the stream cannot deliver whole
 * (a chunked row) parks the project instead of applying half of it.
 *
 * <p>The target is a real H2 database — the point of these tests is the row that lands in
 * the target, not the shape of a prepared statement.
 */
class YStreamCdcConsumerTest {

    private static final String H2_URL =
            "jdbc:h2:mem:ystream_cdc;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private static final long PROJECT = 1L;
    private static final String SERVICE = "JYNC_1_0BADF00D";
    private static final YStreamPosition ANCHOR = new YStreamPosition(100L, 0, 5L, 0, 0);

    private Connection target;
    private MetadataReader reader;
    private DataSourceManager dataSourceManager;
    private SyncLockService.LockHandle lock;
    private CdcPositionStore positionStore;
    private CdcBaselineService baselineService;
    private SyncTaskStore taskStore;
    private YStreamClientFactory clientFactory;
    private YStreamServiceLifecycle lifecycle;
    private SyncContext ctx;
    private YStreamCdcConsumer consumer;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            // The consumer's startup gate reads the source's supplemental log groups.
            st.execute("CREATE TABLE all_log_groups (owner VARCHAR(64),"
                    + " table_name VARCHAR(64), log_group_type VARCHAR(32))");
            st.execute("INSERT INTO all_log_groups VALUES ('APP', 'ORDERS', 'ALL')");
        }

        SyncConfig config = new SyncConfig();
        config.getTables().add("ORDERS");
        config.setObjectSelectionExplicit(true);

        reader = mock(MetadataReader.class);
        when(reader.readTable(any(), eq("APP"), eq("ORDERS")))
                .thenReturn(meta("ORDERS", true));

        positionStore = mock(CdcPositionStore.class);
        baselineService = mock(CdcBaselineService.class);
        taskStore = mock(SyncTaskStore.class);
        clientFactory = mock(YStreamClientFactory.class);
        lifecycle = mock(YStreamServiceLifecycle.class);
        dataSourceManager = mock(DataSourceManager.class);
        try {
            when(dataSourceManager.getConnection(any()))
                    .thenAnswer(invocation -> DriverManager.getConnection(H2_URL, "sa", ""));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);

        ctx = SyncContext.builder()
                .project(project())
                .config(config)
                .sourceConfig(new DatabaseConfig())
                .targetConfig(new DatabaseConfig())
                .sourceSchema("APP")
                .sourceReader(reader)
                .targetDialect(new GenericSqlDialect())
                .batchSize(10)
                .lockRenewer(() -> true)
                .connectionProvider(cfg -> DriverManager.getConnection(H2_URL, "sa", ""))
                .build();

        consumer = new YStreamCdcConsumer(project(), lock, ctx, positionStore, baselineService,
                taskStore, mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties(), clientFactory, lifecycle,
                conn -> new FakeOps());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    private static Project project() {
        Project project = new Project();
        project.setId(PROJECT);
        project.setName("yashan-1");
        return project;
    }

    private static TableMeta meta(String name, boolean withPk) {
        TableMeta meta = new TableMeta();
        meta.setName(name);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(withPk);
        meta.getColumns().add(id);
        ColumnMeta note = new ColumnMeta();
        note.setName("NAME");
        note.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(note);
        if (withPk) {
            meta.getPrimaryKeys().add("ID");
        }
        return meta;
    }

    private static YStreamPosition pos(long scn) {
        return new YStreamPosition(scn, 1, 42L, 0, 0);
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ID", id);
        values.put("NAME", name);
        return values;
    }

    private static YStreamEvent insert(String table, Map<String, Object> values,
                                       YStreamPosition position) {
        return YStreamEvent.row(Operation.INSERT, "APP", table, values, Map.of(), position);
    }

    private static YStreamEvent delete(String table, Map<String, Object> oldValues,
                                       YStreamPosition position) {
        return YStreamEvent.row(Operation.DELETE, "APP", table, Map.of(), oldValues, position);
    }

    private static YStreamEvent update(String table, Map<String, Object> newValues,
                                       Map<String, Object> oldValues, YStreamPosition position) {
        return YStreamEvent.row(Operation.UPDATE, "APP", table, newValues, oldValues, position);
    }

    private void seedTarget(long id, String name) throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("INSERT INTO orders (id, name) VALUES (" + id + ", '" + name + "')");
        }
    }

    private long count(String where) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void handle(YStreamEvent... events) throws Exception {
        for (YStreamEvent event : events) {
            consumer.handle(event);
        }
    }

    // —— one transaction, one target commit ——

    @Test
    void anInsertIsAppliedAtTheCommitAndOnlyThenDoesThePositionAdvance() throws Exception {
        handle(YStreamEvent.begin(pos(1)));
        handle(insert("ORDERS", row(1, "a"), pos(1)));
        assertThat(count("1=1")).isZero();
        verify(positionStore, never()).advanceLsn(any(), anyString());

        handle(YStreamEvent.commit(pos(2)));

        assertThat(count("id=1")).isEqualTo(1);
        verify(positionStore).advanceLsn(PROJECT, YStreamPositionCodec.encode(pos(2)));
    }

    @Test
    void anUncommittedTransactionIsNeverApplied() throws Exception {
        handle(YStreamEvent.begin(pos(1)));
        handle(insert("ORDERS", row(1, "a"), pos(1)));

        assertThat(count("1=1")).isZero();
        verify(positionStore, never()).advanceLsn(any(), anyString());
    }

    @Test
    void anUpdateThatMovesThePrimaryKeyLeavesNoGhostRowBehind() throws Exception {
        seedTarget(1, "old");

        handle(YStreamEvent.begin(pos(1)),
                update("ORDERS", row(2, "new"), row(1, "old"), pos(1)),
                YStreamEvent.commit(pos(2)));

        assertThat(count("id=1")).isZero();
        assertThat(count("id=2")).isEqualTo(1);
    }

    @Test
    void aDeleteIsAppliedFromTheOldImage() throws Exception {
        seedTarget(1, "gone");

        handle(YStreamEvent.begin(pos(1)),
                delete("ORDERS", row(1, "gone"), pos(1)),
                YStreamEvent.commit(pos(2)));

        assertThat(count("1=1")).isZero();
    }

    // —— filtering ——

    @Test
    void rowsOfTablesTheProjectDoesNotTrackAreIgnored() throws Exception {
        handle(YStreamEvent.begin(pos(1)),
                insert("OTHER", row(1, "a"), pos(1)),
                YStreamEvent.commit(pos(2)));

        assertThat(count("1=1")).isZero();
        verify(reader, never()).readTable(any(), anyString(), eq("OTHER"));
    }

    @Test
    void aTableWithoutAPrimaryKeyIsReadOnceAndItsRowsAreSkipped() throws Exception {
        when(reader.readTable(any(), eq("APP"), eq("ORDERS")))
                .thenReturn(meta("ORDERS", false));

        handle(YStreamEvent.begin(pos(1)),
                insert("ORDERS", row(1, "a"), pos(1)),
                insert("ORDERS", row(2, "b"), pos(1)),
                YStreamEvent.commit(pos(2)));

        assertThat(count("1=1")).isZero();
        verify(reader, times(1)).readTable(any(), anyString(), anyString());
    }

    @Test
    void aDdlEventIsSkippedAndTheBindingIsReReadForLaterRows() throws Exception {
        handle(YStreamEvent.begin(pos(1)),
                insert("ORDERS", row(1, "a"), pos(1)),
                YStreamEvent.commit(pos(2)),
                YStreamEvent.ddl("APP", "ORDERS", pos(3)),
                YStreamEvent.begin(pos(4)),
                insert("ORDERS", row(2, "b"), pos(4)),
                YStreamEvent.commit(pos(5)));

        // 表结构变了,缓存的列清单必须失效,否则后续行按旧列写入。
        verify(reader, times(2)).readTable(any(), anyString(), anyString());
        assertThat(count("1=1")).isEqualTo(2);
    }

    // —— whole transactions only ——

    @Test
    void aChunkEventParksTheProject() {
        assertThatThrownBy(() -> consumer.handle(YStreamEvent.chunk("APP", "ORDERS", pos(1))))
                .isInstanceOf(YStreamFatalException.class)
                .hasMessageContaining(YStreamFatalException.MARKER);
    }

    @Test
    void aChunkedRowParksTheProjectInsteadOfApplyingHalfOfIt() {
        YStreamEvent chunked = new YStreamEvent(YStreamEvent.Kind.DML, Operation.INSERT,
                "APP", "ORDERS", row(1, "a"), Map.of(), true, pos(1));

        assertThatThrownBy(() -> consumer.handle(chunked))
                .isInstanceOf(YStreamFatalException.class)
                .hasMessageContaining(YStreamFatalException.MARKER);
    }

    /**
     * A savepoint copies the whole folded buffer, and the copy lives until the commit. A
     * source that sets a savepoint per row therefore grows the heap by everything buffered so
     * far on every savepoint; the budget has to see that growth, or the project OOMs,
     * reconnects, replays the same transaction and OOMs again.
     */
    @Test
    void aSavepointSnapshotCountsAgainstTheTransactionBudget() throws Exception {
        SyncProperties small = new SyncProperties();
        small.getCdc().setMaxTransactionRows(2);
        YStreamCdcConsumer budgeted = new YStreamCdcConsumer(project(), lock, ctx,
                positionStore, baselineService, taskStore, mock(SyncStateWriter.class),
                dataSourceManager, new CdcRuntimeStats(), small, clientFactory, lifecycle,
                conn -> new FakeOps());

        budgeted.handle(YStreamEvent.begin(pos(1)));
        budgeted.handle(insert("ORDERS", row(1, "a"), pos(1)));
        budgeted.handle(insert("ORDERS", row(2, "b"), pos(2)));

        // Two rows fold fine; the savepoint then duplicates both of them.
        assertThatThrownBy(() -> budgeted.handle(YStreamEvent.savepoint(pos(3))))
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class)
                .hasMessageContaining(CdcTransactionBudget.TOO_LARGE_MARKER)
                .hasMessageContaining("savepoint");

        assertThat(count("1=1")).isZero();
        verify(positionStore, never()).advanceLsn(any(), anyString());
    }

    @Test
    void anEmptyCommitStillAdvancesThePosition() throws Exception {
        handle(YStreamEvent.begin(pos(1)));
        handle(YStreamEvent.commit(pos(2)));

        // 不前进就会在每次重连时重放整段空事务。
        verify(positionStore).advanceLsn(PROJECT, YStreamPositionCodec.encode(pos(2)));
    }

    // —— savepoints, end to end ——

    @Test
    void aRollbackToSavepointDropsTheRowsWrittenAfterIt() throws Exception {
        handle(YStreamEvent.begin(pos(1)),
                insert("ORDERS", row(1, "kept"), pos(1)),
                YStreamEvent.savepoint(pos(1)),
                insert("ORDERS", row(2, "rolled back"), pos(1)),
                YStreamEvent.rollbackToSavepoint(pos(1)),
                YStreamEvent.commit(pos(2)));

        assertThat(count("1=1")).isEqualTo(1);
        assertThat(count("id=1")).isEqualTo(1);
    }

    // —— the startup gate ——

    @Test
    void runParksTheProjectWhenASelectedTableHasNoSupplementalLogging() throws Exception {
        try (Statement st = target.createStatement()) {
            st.execute("DELETE FROM all_log_groups");
        }

        consumer.run();

        // 缺补充日志的行不会进流:此时建服务只会得到"看起来在跑但没数据"。
        ArgumentCaptor<String> park = ArgumentCaptor.forClass(String.class);
        verify(taskStore).recordCdcFailure(eq(PROJECT), park.capture());
        assertThat(park.getValue()).contains("YSTREAM_BLOCKED")
                .contains("ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
        verify(lifecycle, never()).ensure(anyLong(), anyList(), any());
    }

    @Test
    void missingSupplementalLoggingComparesNamesCaseInsensitively() throws Exception {
        try (Statement st = target.createStatement()) {
            st.execute("INSERT INTO all_log_groups VALUES ('APP', 'ITEMS', 'ALL')");
        }

        assertThat(YStreamCdcConsumer.missingSupplementalLogging(target,
                List.of("app.orders", "App.Items"))).isEmpty();
        assertThat(YStreamCdcConsumer.missingSupplementalLogging(target,
                List.of("app.other"))).containsExactly("app.other");
    }

    // —— the loop itself ——

    @Test
    void aFatalStreamFailureParksTheProjectInsteadOfReconnecting() throws Exception {
        Deque<YStreamEvent> script = new ArrayDeque<>();
        script.add(YStreamEvent.begin(pos(1)));
        script.add(YStreamEvent.chunk("APP", "ORDERS", pos(1)));
        when(lifecycle.ensure(eq(PROJECT), anyList(), any()))
                .thenReturn(new Outcome(SERVICE, ANCHOR, false));
        when(clientFactory.open(any(), eq(SERVICE), eq(ANCHOR))).thenReturn(adapter(script));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(PROJECT),
                contains(CdcFailureClassifier.Kind.YSTREAM_BLOCKED.name()));
    }

    private static YStreamClientAdapter adapter(Deque<YStreamEvent> script) {
        return new YStreamClientAdapter() {
            @Override
            public YStreamEvent next() {
                return script.poll();
            }

            @Override
            public void close() {
                // nothing to release in the fake
            }
        };
    }

    /** The lifecycle port is not under test here; the consumer's call to it is. */
    private static final class FakeOps implements YStreamServiceOps {
        @Override
        public java.util.List<YStreamServiceAdmin.Service> services(long projectId) {
            return java.util.List.of();
        }

        @Override
        public void create(String serviceName) {
        }

        @Override
        public void addTables(String serviceName, java.util.List<String> qualifiedTables) {
        }

        @Override
        public void start(String serviceName) {
        }

        @Override
        public void stop(String serviceName) {
        }

        @Override
        public void drop(YStreamServiceAdmin.Service service) {
        }

        @Override
        public java.util.Set<String> captured(String serviceName) {
            return java.util.Set.of();
        }
    }
}
