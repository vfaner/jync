package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * End-to-end read/write of values above the inline threshold, with the threshold lowered to
 * 8 bytes so an ordinary test payload exercises the streaming path. The H2 target is a
 * generic dialect, so writes go through the emulated upsert — the path that binds each row
 * more than once and therefore requires spill-to-disk instead of one-shot streams.
 */
class DataSyncServiceLobStreamingTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    @TempDir
    Path spillDir;

    private Connection source;
    private Connection target;
    private SyncProperties properties;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:lob_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:lob_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB,"
                + " PAYLOAD BLOB, NOTE VARCHAR(2000))");
        exec(target, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB,"
                + " PAYLOAD BLOB, NOTE VARCHAR(2000))");

        properties = new SyncProperties();
        properties.setChunkSize(0);
        properties.setBatchSize(50);
        properties.getLob().setInlineMaxBytes(8);
        properties.getLob().setSpillDir(spillDir.toString());

        ColumnRule trim = new ColumnRule();
        trim.setTable("DOCS");
        trim.setColumn("NOTE");
        trim.setType(ColumnRuleType.TRIM);
        SyncConfig config = new SyncConfig();
        config.setColumnRules(List.of(trim));

        Project project = new Project();
        project.setId(9L);
        ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.CUSTOM)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .fullLoadBatchSize(50)
                .fullLoadFetchSize(100)
                .fullLoadParallelism(1)
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
    void largeClobAndBlobSurviveTheStreamedPath() throws SQLException {
        String body = "中".repeat(50_000);
        byte[] payload = new byte[40_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        insert(body, payload, "  keep me  ");

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT BODY, PAYLOAD, NOTE FROM DOCS")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("BODY")).isEqualTo(body);
            assertThat(rs.getBytes("PAYLOAD")).isEqualTo(payload);
            // The cleansed column is force-inlined and still gets its rule applied.
            assertThat(rs.getString("NOTE")).isEqualTo("keep me");
        }
    }

    @Test
    void valuesBelowTheInlineThresholdStayOnThePlainPath() throws SQLException {
        insert("short", new byte[] {1, 2, 3}, "tiny");

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT BODY, PAYLOAD FROM DOCS")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("BODY")).isEqualTo("short");
            assertThat(rs.getBytes("PAYLOAD")).containsExactly(1, 2, 3);
        }
    }

    @Test
    void streamingCanBeDisabledForARollbackToTheOldBehaviour() throws SQLException {
        properties.getLob().setStreamingEnabled(false);
        String body = "x".repeat(20_000);
        insert(body, new byte[10_000], "n");

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT BODY FROM DOCS")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("BODY")).isEqualTo(body);
        }
    }

    /**
     * The native-upsert path keeps values one-shot instead of spilling them, so the writer
     * must flush the pending batch and execute that row alone before the cursor advances.
     * H2's MySQL mode accepts the same {@code ON DUPLICATE KEY UPDATE} statement the MySQL
     * dialect emits, which makes it a stand-in for a native target.
     */
    @Test
    void oneShotValuesUseTheNativeUpsertPath() throws SQLException {
        String body = "z".repeat(60_000);
        insert(body, new byte[] {7, 7, 7}, "n");

        try (Connection my = DriverManager.getConnection(
                "jdbc:h2:mem:lob_tgt_my;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = my.createStatement()) {
            st.execute("CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB,"
                    + " PAYLOAD BLOB, NOTE VARCHAR(2000))");
            SyncContext myCtx = ctx.toBuilder()
                    .targetDialect(new com.qqmu.jync.service.converter.MySqlDialect())
                    .targetType(DatabaseType.MYSQL)
                    .build();

            DataSyncService.TableSyncResult result = new DataSyncService(properties,
                    mock(ChunkCheckpointRepository.class))
                    .syncTable(source, my, docsMeta(), IDENTITY, new SyncProgress(), myCtx);

            assertThat(result.isSuccess()).as(result.getError()).isTrue();
            try (Statement check = my.createStatement();
                 ResultSet rs = check.executeQuery("SELECT BODY, PAYLOAD FROM DOCS")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("BODY")).isEqualTo(body);
                assertThat(rs.getBytes("PAYLOAD")).containsExactly(7, 7, 7);
            }
        }
    }

    @Test
    void spilledFilesAreDeletedWhenTheCopyEnds() throws SQLException {
        insert("y".repeat(30_000), new byte[30_000], "n");

        assertThat(sync().isSuccess()).isTrue();

        assertThat(spillFiles()).isZero();
    }

    /** A one-byte budget forces a flush before every row; all rows must still land intact. */
    @Test
    void byteBudgetFlushesBetweenRowsWithoutLosingData() throws SQLException {
        properties.getLob().setBatchMaxBytes(1);
        insertRow(1, "a".repeat(1000));
        insertRow(2, "b".repeat(1000));
        insertRow(3, "c".repeat(1000));

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT ID, BODY FROM DOCS ORDER BY ID")) {
            for (int id = 1; id <= 3; id++) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("ID")).isEqualTo(id);
                assertThat(rs.getString("BODY")).hasSize(1000);
            }
        }
        assertThat(spillFiles()).isZero();
    }

    /** A failed copy must still delete every spilled file before the error surfaces. */
    @Test
    void spillFilesAreDeletedWhenTheCopyFails() throws SQLException {
        exec(target, "DROP TABLE DOCS");
        exec(target, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY CHECK (ID < 2),"
                + " BODY CLOB, PAYLOAD BLOB, NOTE VARCHAR(2000))");
        insertRow(1, "p".repeat(20_000));
        insertRow(2, "q".repeat(20_000));

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).isFalse();
        assertThat(spillFiles()).isZero();
    }

    private void insertRow(long id, String body) throws SQLException {
        try (PreparedStatement ps = source.prepareStatement(
                "INSERT INTO DOCS (ID, BODY, PAYLOAD, NOTE) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setCharacterStream(2, new StringReader(body), body.length());
            ps.setBytes(3, new byte[] {1});
            ps.setString(4, "n");
            ps.executeUpdate();
        }
    }

    private DataSyncService.TableSyncResult sync() throws SQLException {
        DataSyncService service = new DataSyncService(properties,
                mock(ChunkCheckpointRepository.class));
        return service.syncTable(source, target, docsMeta(), IDENTITY, new SyncProgress(), ctx);
    }

    private void insert(String body, byte[] payload, String note) throws SQLException {
        try (PreparedStatement ps = source.prepareStatement(
                "INSERT INTO DOCS (ID, BODY, PAYLOAD, NOTE) VALUES (1, ?, ?, ?)")) {
            ps.setCharacterStream(1, new StringReader(body), body.length());
            ps.setBytes(2, payload);
            ps.setString(3, note);
            ps.executeUpdate();
        }
    }

    private long spillFiles() throws SQLException {
        try (Stream<Path> files = Files.list(spillDir)) {
            return files.count();
        } catch (java.io.IOException e) {
            throw new SQLException(e);
        }
    }

    private static TableMeta docsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta body = new ColumnMeta();
        body.setName("BODY");
        body.setJdbcType(Types.CLOB);
        ColumnMeta payload = new ColumnMeta();
        payload.setName("PAYLOAD");
        payload.setJdbcType(Types.BLOB);
        ColumnMeta note = new ColumnMeta();
        note.setName("NOTE");
        note.setJdbcType(Types.VARCHAR);
        note.setSize(2000);
        TableMeta table = new TableMeta();
        table.setName("DOCS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, body, payload, note));
        table.setApproximateRowCount(1L);
        return table;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
