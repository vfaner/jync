package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The detail page flags a table whose cursor column has no index at the source.
 *
 * <p>Without a leading index every incremental read scans the whole source table instead of
 * a key range — the read that eventually reaches the statement timeout. The page has to say
 * so, because the sync itself cannot: on a healthy small table it just looks slow, and by the
 * time the table is large the fix is an index nobody was ever asked to create.
 *
 * <p>Runs against a real H2 dictionary rather than a scripted one: the flag depends on what
 * the driver reports for ORDINAL_POSITION and TABLE_NAME, which a mock would only restate.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceCursorIndexViewTest {

    private static final String URL = "jdbc:h2:mem:cursor_view;DB_CLOSE_DELAY=-1";

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private CdcPositionStore positionStore;
    @Mock private com.qqmu.jync.repository.TaskMetricSampleRepository taskMetricSampleRepository;
    @Mock private com.qqmu.jync.repository.AlertStateRepository alertStateRepository;

    private final SyncProperties properties = new SyncProperties();

    private ProjectService service;
    private DatabaseConfig source;
    private MetadataReader reader;

    @BeforeEach
    void setUp() throws SQLException {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                taskMetricSampleRepository, alertStateRepository, properties);

        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setSourceDbId(100L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        source = new DatabaseConfig();
        source.setId(100L);
        source.setName("src");
        source.setType(DatabaseType.H2);
        // Declared, so the page never has to ask the connection for its default schema.
        source.setSchemaName("PUBLIC");
        lenient().when(databaseConfigRepository.findById(100L)).thenReturn(Optional.of(source));

        reader = mock(MetadataReader.class);
        lenient().when(readerFactory.forType(eq(DatabaseType.H2), any())).thenReturn(reader);
        lenient().when(dataSourceManager.getConnection(source)).thenAnswer(inv -> open());
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    private void execute(String... sql) throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String one : sql) {
                st.execute(one);
            }
        }
    }

    private void sourceLists(String... tables) throws SQLException {
        when(reader.listTableNames(any(Connection.class), eq("PUBLIC")))
                .thenReturn(List.of(tables));
    }

    private static SyncProgress cursor(String table, String column) {
        SyncProgress p = new SyncProgress();
        p.setProjectId(1L);
        p.setObjectType(ObjectType.TABLE);
        p.setObjectName(table);
        p.setCursorColumn(column);
        return p;
    }

    private Set<String> flagged(SyncProgress... rows) {
        return service.findUnindexedCursorTables(1L, new ArrayList<>(List.of(rows)));
    }

    @Test
    void flagsTheTableWhoseCursorColumnLeadsNoIndex() throws SQLException {
        execute("CREATE TABLE EVENTS (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP, V VARCHAR(32))",
                "CREATE TABLE LOGS (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP, V VARCHAR(32))",
                "CREATE INDEX IDX_LOGS_UPDATED ON LOGS (UPDATED)");
        sourceLists("EVENTS", "LOGS");

        // Both tables sync by the same column; only the one without the index pays with a
        // full scan per window.
        assertThat(flagged(cursor("EVENTS", "UPDATED"), cursor("LOGS", "UPDATED")))
                .containsExactly("EVENTS");
    }

    @Test
    void aColumnThatOnlyTrailsAnIndexDoesNotCount() throws SQLException {
        execute("CREATE TABLE PAIRS (A BIGINT, B BIGINT, PRIMARY KEY (A, B))");
        sourceLists("PAIRS");

        // The composite key leads with A: reading by B cannot use it, so B is unindexed in
        // the only sense that matters here.
        assertThat(flagged(cursor("PAIRS", "B"))).containsExactly("PAIRS");
        assertThat(flagged(cursor("PAIRS", "A"))).isEmpty();
    }

    @Test
    void rowsWithoutACursorColumnAreNotJudged() throws SQLException {
        execute("CREATE TABLE CATS (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP)");
        sourceLists("CATS");

        // FULL_COMPARE / not-yet-resolved rows have no column to index, and the page must not
        // send the operator looking for one.
        assertThat(flagged(cursor("CATS", null))).isEmpty();
        assertThat(flagged(cursor("CATS", "  "))).isEmpty();
    }

    @Test
    void aTableTheSourceNoLongerListsIsNotFlagged() throws SQLException {
        execute("CREATE TABLE KEPT (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP)");
        sourceLists("KEPT");

        // A stale progress row (dropped or renamed table, or a view) cannot be inspected;
        // guessing "unindexed" would put the warning on a row nobody can act on.
        assertThat(flagged(cursor("GONE", "UPDATED"), cursor("KEPT", "UPDATED")))
                .containsExactly("KEPT");
    }

    @Test
    void aFailedSourceReadYieldsNoHintInsteadOfAnError() throws SQLException {
        when(dataSourceManager.getConnection(source))
                .thenThrow(new IllegalStateException("error.connection.failed"));

        // The hint is advisory: an unreachable source must leave the page alone, exactly
        // like the source-objects listing does.
        assertThat(flagged(cursor("EVENTS", "UPDATED"))).isEmpty();
    }

    @Test
    void anEmptyProgressListCostsNothing() throws SQLException {
        assertThat(service.findUnindexedCursorTables(1L, List.of())).isEmpty();

        // No progress means no cursor column anywhere — not worth a source round trip.
        org.mockito.Mockito.verify(dataSourceManager, org.mockito.Mockito.never())
                .getConnection(any());
    }
}
