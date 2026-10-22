package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The PG slot buffer holds whole decoded rows until the commit boundary. Oversized text
 * cells are spilled for the duration of that transaction and released at every boundary:
 * commit, BEGIN (defensive re-peek), and a half transaction abandoned at the cycle end.
 */
class PgLogicalCdcConsumerLobTest {

    private static final String H2_URL =
            "jdbc:h2:mem:pg_lob_drain;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    @TempDir
    Path spillDir;

    private Connection source;
    private PreparedStatement peekPs;
    private PreparedStatement advancePs;
    private final Deque<ResultSet> script = new ArrayDeque<>();
    private SyncProperties properties;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE docs (id BIGINT PRIMARY KEY, body CLOB)");
        }
        source = mock(Connection.class);
        when(source.getAutoCommit()).thenReturn(true);
        peekPs = mock(PreparedStatement.class);
        advancePs = mock(PreparedStatement.class);
        when(source.prepareStatement(contains("peek_changes"))).thenReturn(peekPs);
        when(source.prepareStatement(contains("slot_advance"))).thenReturn(advancePs);
        when(peekPs.executeQuery()).thenAnswer(inv -> script.poll());
        ResultSet advanceRs = mock(ResultSet.class);
        when(advanceRs.next()).thenReturn(true);
        when(advancePs.executeQuery()).thenReturn(advanceRs);

        properties = new SyncProperties();
        properties.getLob().setCdcSpillThresholdBytes(16);
        properties.getLob().setSpillDir(spillDir.toString());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void aLargeValueCrossesTheTransactionBufferAndLandsIntact() throws Exception {
        scriptCall(new String[] {"0/1000", "BEGIN 1"},
                new String[] {"0/1001", insert(1L, "中".repeat(200))},
                new String[] {"0/1002", "COMMIT 1"});

        pgConsumer().drain(source, "jync_1");

        assertThat(readBody()).isEqualTo("中".repeat(200));
        assertThat(spillFiles()).isZero();
    }

    @Test
    void aHalfTransactionAbandonedAtTheCycleEndLeavesNoFiles() throws Exception {
        // A budget cut mid-transaction: the buffer is discarded, the slot is not advanced.
        scriptCall(new String[] {"0/1000", "BEGIN 1"},
                new String[] {"0/1001", insert(1L, "x".repeat(200))});

        pgConsumer().drain(source, "jync_1");

        assertThat(spillFiles()).isZero();
        assertThat(targetRowCount()).isZero();
    }

    @Test
    void exceedingTheSpillCapFailsTheTransactionInsteadOfWritingHalfARow() throws Exception {
        properties.getLob().setSpillMaxBytes(8);
        scriptCall(new String[] {"0/1000", "BEGIN 1"},
                new String[] {"0/1001", insert(1L, "x".repeat(200))},
                new String[] {"0/1002", "COMMIT 1"});

        assertThatThrownBy(() -> pgConsumer().drain(source, "jync_1"))
                .hasMessageContaining("sync.lob.spill-max-bytes");
        verify(advancePs, never()).executeQuery();
        assertThat(targetRowCount()).isZero();
    }

    // --- fixtures -------------------------------------------------------------------

    private PgLogicalCdcConsumer pgConsumer() throws SQLException {
        TableMeta meta = new TableMeta();
        meta.setName("docs");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("body", Types.LONGVARCHAR, false));
        meta.getPrimaryKeys().add("id");
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);

        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    try {
                        return h2conn();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);
        Project project = new Project();
        project.setId(1L);
        project.setName("pg-lob");
        return new PgLogicalCdcConsumer(project, mock(SyncLockService.LockHandle.class), ctx,
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties);
    }

    private void scriptCall(String[]... rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Boolean[] nexts = new Boolean[rows.length + 1];
        java.util.Arrays.fill(nexts, 0, rows.length, Boolean.TRUE);
        nexts[rows.length] = Boolean.FALSE;
        when(rs.next()).thenReturn(nexts[0],
                java.util.Arrays.copyOfRange(nexts, 1, nexts.length));
        String[] lsns = new String[rows.length];
        String[] datas = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            lsns[i] = rows[i][0];
            datas[i] = rows[i][1];
        }
        when(rs.getString(1)).thenReturn(lsns[0],
                java.util.Arrays.copyOfRange(lsns, 1, lsns.length));
        when(rs.getString(2)).thenReturn(datas[0],
                java.util.Arrays.copyOfRange(datas, 1, datas.length));
        script.add(rs);
    }

    private static String insert(long id, String body) {
        return "table public.docs: INSERT: id[bigint]:" + id + " body[text]:'" + body + "'";
    }

    private String readBody() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT body FROM docs")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long targetRowCount() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM docs")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long spillFiles() {
        try (Stream<Path> files = Files.list(spillDir)) {
            return files.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }
}
