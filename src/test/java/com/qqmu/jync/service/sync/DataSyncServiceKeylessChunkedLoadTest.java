package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Keyless tables have no key to page over, so the chunked load walks them with OFFSET and
 * derives the resume point from the <em>target's</em> row count rather than from the last
 * checkpoint: a crash between a chunk's commit and its checkpoint write leaves the rows in the
 * target, and trusting the checkpoint would copy that chunk again as duplicates.
 *
 * <p>The shortcut only applies to a table confirmed empty at load start. Any other target keeps
 * the classic single streamed read: rows already there cannot be told apart from a half-done
 * load, and an OFFSET resume would silently duplicate them.
 */
class DataSyncServiceKeylessChunkedLoadTest {

    private static final CursorStrategy FULL_ONLY = CursorStrategy.none("full load only");
    private static final String SRC_URL = "jdbc:h2:mem:keyless_src;DB_CLOSE_DELAY=-1";
    private static final String TGT_URL = "jdbc:h2:mem:keyless_tgt;DB_CLOSE_DELAY=-1";

    private Connection source;
    private Connection target;
    private ChunkCheckpointRepository checkpoints;
    private SyncContext ctx;
    private final List<String> prepared = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(SRC_URL, "sa", "");
        target = DriverManager.getConnection(TGT_URL, "sa", "");
        checkpoints = mock(ChunkCheckpointRepository.class);
        when(checkpoints.findByProjectIdAndTableName(7L, "EVENTS")).thenReturn(List.of());
        ctx = context();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void aKeylessTableLoadsInOffsetChunks() throws SQLException {
        createTables();
        insertEvents(1, 5);

        DataSyncService.TableSyncResult result =
                service(2).syncTable(record(source), target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count("EVENTS")).isEqualTo(5);
        assertThat(result.getRowsWritten()).isEqualTo(5);
        assertThat(result.getResumableLoad()).isTrue();

        ArgumentCaptor<ChunkCheckpoint> saved =
                ArgumentCaptor.forClass(ChunkCheckpoint.class);
        verify(checkpoints, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(c ->
                assertThat(c.getStatus()).isEqualTo(ChunkCheckpoint.STATUS_KEYLESS_CHUNK));
        // A marker is written before the first page (see the crash test below), then the
        // offsets are recorded as rows copied so far, one after each committed page.
        assertThat(saved.getAllValues()).extracting(ChunkCheckpoint::getRowsCopied)
                .containsExactly(0, 2, 4, 5);
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "EVENTS");

        assertThat(prepared).anyMatch(sql -> sql.contains("ORDER BY"));
        assertThat(prepared.get(0)).contains("OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY");
        assertThat(prepared.get(1)).contains("OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY");
    }

    @Test
    void aResumedLoadStartsFromTheTargetRowCountNotTheCheckpoint() throws SQLException {
        createTables();
        insertEvents(1, 5);
        // The third row's page committed on the target, but the crash happened before the
        // checkpoint write: the saved offset (2) is stale, the target (3 rows) is the truth.
        insertEvents(target, 1, 3);
        when(checkpoints.findByProjectIdAndTableName(7L, "EVENTS")).thenReturn(List.of(
                new ChunkCheckpoint(7L, "EVENTS", 0, 1, null, 2,
                        ChunkCheckpoint.STATUS_KEYLESS_CHUNK)));

        DataSyncService.TableSyncResult result =
                service(2).syncTable(source, target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Exactly the whole source, with no duplicated prefix.
        assertThat(count("EVENTS")).isEqualTo(5);
        assertThat(result.getRowsWritten()).isEqualTo(2);
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "EVENTS");
    }

    @Test
    void aPageCommittedBeforeItsCheckpointIsResumedInsteadOfCopiedAgain() throws SQLException {
        createTables();
        insertEvents(1, 5);
        // The checkpoint repository holds exactly what survived each step, so the second
        // attempt below sees the state a real crash leaves behind.
        List<ChunkCheckpoint> stored = new ArrayList<>();
        AtomicBoolean crashed = new AtomicBoolean();
        when(checkpoints.findByProjectIdAndTableName(7L, "EVENTS"))
                .thenAnswer(invocation -> List.copyOf(stored));
        doAnswer(invocation -> {
            ChunkCheckpoint checkpoint = invocation.getArgument(0);
            // Die after the first page's commit, before its checkpoint row is written.
            if (checkpoint.getRowsCopied() > 0 && crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("process died before the checkpoint write");
            }
            stored.add(checkpoint);
            return null;
        }).when(checkpoints).save(any(ChunkCheckpoint.class));
        doAnswer(invocation -> {
            stored.clear();
            return null;
        }).when(checkpoints).deleteByProjectIdAndTableName(7L, "EVENTS");

        assertThatThrownBy(() -> service(2).syncTable(source, target, eventsMeta(), FULL_ONLY,
                new SyncProgress(), ctx)).isInstanceOf(IllegalStateException.class);
        // The first page did reach the target before the crash.
        assertThat(count("EVENTS")).isEqualTo(2);

        DataSyncService.TableSyncResult result =
                service(2).syncTable(source, target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        // Without a marker written before the first page, an empty checkpoint table reads as
        // "a target somebody else filled": the load falls back to the single streamed read and
        // plain-INSERTs the whole source, duplicating the page the crash left behind. A keyless
        // table has no key to absorb that duplicate.
        assertThat(count("EVENTS")).isEqualTo(5);
    }

    @Test
    void aNonEmptyTargetWithoutResumeStateKeepsTheClassicLoad() throws SQLException {
        createTables();
        insertEvents(1, 5);
        insertEvents(target, 99, 99);

        DataSyncService.TableSyncResult result =
                service(2).syncTable(source, target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getResumableLoad()).isFalse();
        verify(checkpoints, never()).save(any(ChunkCheckpoint.class));
        // The classic load cannot dedupe; that behavior is unchanged and the UI says so.
        assertThat(count("EVENTS")).isEqualTo(6);
    }

    @Test
    void anEmptyKeylessTableFinalizesWithoutPages() throws SQLException {
        createTables();

        DataSyncService.TableSyncResult result =
                service(2).syncTable(source, target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getRowsWritten()).isZero();
        assertThat(result.getResumableLoad()).isTrue();
        verify(checkpoints).deleteByProjectIdAndTableName(7L, "EVENTS");
    }

    @Test
    void aTableWithOnlyLobColumnsKeepsTheClassicLoad() throws SQLException {
        // Neither Oracle nor SQL Server can ORDER BY a LOB, and without a deterministic order
        // an OFFSET resume could skip or repeat rows. Such tables keep the streamed read.
        exec(source, "CREATE TABLE DOCS (BODY CLOB, RAW_BODY BLOB)");
        exec(target, "CREATE TABLE DOCS (BODY CLOB, RAW_BODY BLOB)");
        exec(source, "INSERT INTO DOCS VALUES ('a', X'00'), ('b', X'01')");

        DataSyncService.TableSyncResult result =
                service(2).syncTable(record(source), target, lobsOnlyMeta(), FULL_ONLY,
                        new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(result.getResumableLoad()).isFalse();
        verify(checkpoints, never()).save(any(ChunkCheckpoint.class));
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM DOCS")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(2);
        }
    }

    @Test
    void aSourceThatCannotPageByOffsetKeepsTheClassicLoad() throws SQLException {
        createTables();
        insertEvents(1, 2);

        // SQL Server before 2012 and DB2 before 11.1 cannot express a non-zero offset. The walk
        // must not start at all: failing on page two would strand a load whose page one is
        // already committed.
        DataSyncService.TableSyncResult result =
                service(1).syncTable(source, target, eventsMeta(), FULL_ONLY,
                        new SyncProgress(), context(new GenericSqlDialect() {
                            @Override
                            public boolean supportsOffsetPaging() {
                                return false;
                            }
                        }));

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(count("EVENTS")).isEqualTo(2);
        assertThat(result.getResumableLoad()).isFalse();
        verify(checkpoints, never()).save(any(ChunkCheckpoint.class));
    }

    @Test
    void orderColumnsSkipLobsAndMissingWhenNothingIsOrderable() {
        assertThat(DataSyncService.keylessOrderColumns(eventsMeta()))
                .containsExactly("SEQ", "NAME");

        TableMeta mixed = new TableMeta();
        mixed.setName("DOCS");
        ColumnMeta body = column("BODY", Types.CLOB);
        ColumnMeta name = column("NAME", Types.VARCHAR);
        mixed.setColumns(List.of(body, name));
        assertThat(DataSyncService.keylessOrderColumns(mixed)).containsExactly("NAME");

        TableMeta lobs = new TableMeta();
        lobs.setName("DOCS");
        lobs.setColumns(List.of(column("BODY", Types.CLOB), column("RAW_BODY", Types.BLOB)));
        assertThat(DataSyncService.keylessOrderColumns(lobs)).isEmpty();
    }

    private DataSyncService service(int chunkSize) {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(chunkSize);
        properties.setFullLoadParallelism(1);
        return new DataSyncService(properties, checkpoints);
    }

    private void createTables() throws SQLException {
        String ddl = "CREATE TABLE EVENTS (SEQ BIGINT, NAME VARCHAR(50))";
        exec(source, ddl);
        exec(target, ddl);
    }

    private void insertEvents(int from, int to) throws SQLException {
        insertEvents(source, from, to);
    }

    private static void insertEvents(Connection conn, int from, int to) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO EVENTS VALUES ");
        for (int i = from; i <= to; i++) {
            sql.append(i > from ? ", " : "").append('(').append(i).append(", 'n")
                    .append(i).append("')");
        }
        exec(conn, sql.toString());
    }

    private long count(String table) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Keyless: no primary keys, just ordinary columns. */
    private static TableMeta eventsMeta() {
        TableMeta table = new TableMeta();
        table.setName("EVENTS");
        table.setPrimaryKeys(List.of());
        table.setColumns(List.of(column("SEQ", Types.BIGINT), column("NAME", Types.VARCHAR)));
        return table;
    }

    private static TableMeta lobsOnlyMeta() {
        TableMeta table = new TableMeta();
        table.setName("DOCS");
        table.setPrimaryKeys(List.of());
        table.setColumns(List.of(column("BODY", Types.CLOB),
                column("RAW_BODY", Types.BLOB)));
        return table;
    }

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private static SyncContext context() {
        return context(new GenericSqlDialect());
    }

    private static SyncContext context(SqlDialect sourceDialect) {
        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(1L);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(2L);
        Project project = new Project();
        project.setId(7L);
        return SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceDialect(sourceDialect)
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    /** Records every prepared SQL while delegating to the real source connection. */
    private Connection record(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                DataSyncServiceKeylessChunkedLoadTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] instanceof String) {
                        prepared.add((String) args[0]);
                    }
                    Object result = invoke(real, method, args);
                    if (result instanceof java.sql.PreparedStatement) {
                        return result;
                    }
                    return result;
                });
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
