package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(checkpoints.findByProjectIdAndTableNameOrderByChunkIndexDesc(7L, "ITEMS"))
                .thenReturn(List.of(new ChunkCheckpoint(7L, "ITEMS", 1, "[4]", 2)));
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
