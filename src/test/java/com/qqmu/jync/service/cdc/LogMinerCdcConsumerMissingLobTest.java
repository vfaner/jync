package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * LogMiner cannot render the value of a LOB stored out of line, so a change carrying such a
 * column is refused by the parser. What happens next is the user's choice:
 * <ul>
 *   <li>{@code missing-lob-policy=skip} (default, 1.7.7 behaviour): count it, warn with the
 *       table name and the running tally (throttled), keep the project running, and let the
 *       operator re-baseline when the drift matters;</li>
 *   <li>{@code missing-lob-policy=fail}: park the project instead of applying a stream that
 *       is now permanently incomplete — and never advance the position past the skipped
 *       change, so a re-baseline resumes from the right place.</li>
 * </ul>
 */
class LogMinerCdcConsumerMissingLobTest {

    private static final String H2_URL =
            "jdbc:h2:mem:lm_missing_lob;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncTaskStore taskStore;
    private SyncStateWriter stateWriter;
    private SyncProperties properties;
    private CdcRuntimeStats stats;
    private final AtomicInteger cycle = new AtomicInteger();

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE og_lob (id BIGINT PRIMARY KEY, blob_in BLOB)");
        }

        source = mock(Connection.class);
        Statement probeStmt = mock(Statement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(probeStmt.executeQuery(anyString())).thenReturn(emptyRs);
        when(source.createStatement()).thenReturn(probeStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setScn(1_000L);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(java.util.Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        taskStore = mock(SyncTaskStore.class);
        stateWriter = mock(SyncStateWriter.class);
        stats = new CdcRuntimeStats();
        properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getLogminer().setIdlePollMs(1);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void theDefaultPolicySkipsCountsAndKeepsTheProjectRunning() throws Exception {
        LogMinerCdcConsumer consumer = newConsumer(singleMissingLobSession(), boundedLock(2));

        consumer.run();

        assertThat(stats.getSkippedMissingLob()).isEqualTo(1);
        assertThat(stats.missingLobByTable()).containsEntry("SRC.OG_LOB", 1L);
        assertThat(targetRowCount()).isZero();
        verify(taskStore, never()).recordCdcFailure(anyLong(), anyString());
    }

    @Test
    void theFailPolicyParksTheProjectWithoutAdvancingThePosition() throws Exception {
        properties.getCdc().getLogminer().setMissingLobPolicy("fail");
        LogMinerCdcConsumer consumer = newConsumer(singleMissingLobSession(), boundedLock(20));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L),
                contains("sync.cdc.logminer.missing-lob-policy"));
        verify(positionStore, never()).advanceScn(anyLong(), anyLong());
        assertThat(targetRowCount()).isZero();
        assertThat(stats.getSkippedMissingLob()).isEqualTo(1);
    }

    // --- fixtures -------------------------------------------------------------------

    /** One INSERT whose redo text omits the out-of-line CLOB/BLOB column entirely. */
    private LogMinerSession singleMissingLobSession() {
        int n = cycle.get() + 1;
        String redo = "insert into \"SRC\".\"OG_LOB\"(\"ID\") values ('" + n + "');";
        LogMinerSession.Row row = new LogMinerSession.Row("rs", 1_000L + n,
                "INSERT", "SRC", "OG_LOB", redo, new Timestamp(System.currentTimeMillis()), 0);
        return new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            private boolean consumed;

            @Override
            public List<LogMinerSession.Row> nextBatch() {
                if (consumed) {
                    return Collections.emptyList();
                }
                consumed = true;
                return Collections.singletonList(row);
            }

            @Override
            public long highWaterScn() {
                return 1_000L + n;
            }

            @Override
            public void close() {
                // END_LOGMNR would run on a null source connection; nothing to do here.
            }
        };
    }

    private TableMeta ogLobMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("og_lob");
        meta.getColumns().add(column("ID", Types.BIGINT, true));
        meta.getColumns().add(column("BLOB_IN", Types.BLOB, false));
        meta.getPrimaryKeys().add("ID");
        return meta;
    }

    private LogMinerCdcConsumer newConsumer(LogMinerSession session,
                                            SyncLockService.LockHandle lock) throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(ogLobMeta());
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> h2conn())
                .build();

        Project project = new Project();
        project.setId(1L);
        project.setName("lm-lob");
        LogMinerSessionFactory factory = (c, s, b) -> session;
        return new LogMinerCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore, stateWriter, dataSourceManager,
                stats, properties, factory);
    }

    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }

    private int targetRowCount() throws SQLException {
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM og_lob")) {
            rs.next();
            return rs.getInt(1);
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
