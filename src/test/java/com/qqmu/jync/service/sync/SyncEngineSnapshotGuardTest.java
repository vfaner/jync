package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * The per-table snapshot is the detector's memory: writing it after a change failed or was
 * skipped tells the detector the change landed, and it is never retried. These tests pin the
 * withholding rules —
 *
 * <ul>
 *   <li>a table-shaped event (TABLE/COLUMN/INDEX) that failed or was skipped blocks the whole
 *       table's snapshot update, no matter what else succeeded for that table;</li>
 *   <li>views and routines keep their immediate per-object snapshotting;</li>
 *   <li>the data phase must not undo the withholding with its own end-of-table refresh, and
 *       must not refresh at all while structure sync is off (that would silently consume
 *       structural diffs the user has not asked to apply);</li>
 *   <li>a lost lock lease aborts the cycle before the next table's writes.</li>
 * </ul>
 */
class SyncEngineSnapshotGuardTest {

    private static final long PROJECT_ID = 7L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;

    private Connection sourceConn;
    private Connection targetConn;
    private TableMeta orders;
    private DatabaseMeta meta;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:eng_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:eng_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        DatabaseConfig sourceCfg = new DatabaseConfig();
        DatabaseConfig targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        orders = new TableMeta();
        orders.setName("ORDERS");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        orders.setColumns(List.of(id));
        meta = new DatabaseMeta();
        meta.setTables(List.of(orders));

        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(reader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("demo");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(orders));

        // Keep these reachable from every test's builder call.
        this.sourceCfg = sourceCfg;
        this.targetCfg = targetCfg;
        this.reader = reader;
    }

    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private MetadataReader reader;

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    private SyncContext ctx(boolean syncStructure, boolean syncData) {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(syncStructure);
        config.setSyncData(syncData);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(reader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    private ChangeEvent columnAlter() {
        ColumnMeta col = new ColumnMeta();
        col.setName("AMOUNT");
        ChangeEvent event = ChangeEvent.of(ObjectType.COLUMN, ChangeType.ALTER, "ORDERS",
                "Column AMOUNT changed", col);
        event.setDetailName("AMOUNT");
        return event;
    }

    // --- structure phase ---------------------------------------------------------------

    @Test
    void aFailedColumnChangeWithholdsTheTableSnapshot() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of(columnAlter()));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.failed("ORA-01442 boom"));

        SyncResult result = engine.runCycle(ctx(true, false));

        assertThat(result.isSuccess()).isFalse();
        // The snapshot is the retry record: saving the new shape here would lose the change.
        verify(stateWriter, never()).saveSnapshot(anyLong(), any(), anyString(), any());
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.COLUMN), eq("ORDERS"),
                eq(ChangeType.ALTER), eq("ORA-01442 boom"), eq(false), anyLong());
    }

    @Test
    void aSkippedColumnChangeWithholdsTheTableSnapshotToo() throws Exception {
        // A skip means the target still holds the old shape — the same lie would be told.
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of(columnAlter()));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome
                        .skipped("Target dialect cannot alter column types in place"));

        engine.runCycle(ctx(true, false));

        verify(stateWriter, never()).saveSnapshot(anyLong(), any(), anyString(), any());
    }

    @Test
    void anAppliedColumnChangeRefreshesTheTableSnapshot() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of(columnAlter()));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Modified column AMOUNT"));

        engine.runCycle(ctx(true, false));

        // A column event refreshes the whole table snapshot, keyed by the table name.
        verify(stateWriter).saveSnapshot(PROJECT_ID, ObjectType.TABLE, "ORDERS", orders);
    }

    @Test
    void oneFailedEventBlocksTheWholeTableEvenIfAnotherSucceeded() throws Exception {
        ChangeEvent indexEvent = ChangeEvent.of(ObjectType.INDEX, ChangeType.CREATE, "ORDERS",
                "New index", new IndexMeta());
        when(changeDetector.detect(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(columnAlter(), indexEvent));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Modified column AMOUNT"))
                .thenReturn(StructureSyncService.ApplyOutcome.failed("index rejected"));

        engine.runCycle(ctx(true, false));

        // The table is half-converged; its snapshot must keep the remainder detectable.
        verify(stateWriter, never()).saveSnapshot(anyLong(), any(), anyString(), any());
    }

    @Test
    void viewsSnapshotImmediatelyEvenWhileATableIsBlocked() throws Exception {
        ViewMeta view = new ViewMeta();
        view.setName("V_ORDERS");
        view.setDefinition("SELECT 1");
        ChangeEvent viewEvent = ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE, "V_ORDERS",
                "New view", view);

        when(changeDetector.detect(eq(PROJECT_ID), any(), any()))
                .thenReturn(List.of(columnAlter(), viewEvent));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.failed("boom"))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created view V_ORDERS"));

        engine.runCycle(ctx(true, false));

        // Views are per-object snapshots: one blocked table must not hold them hostage.
        verify(stateWriter).saveSnapshot(PROJECT_ID, ObjectType.VIEW, "V_ORDERS", view);
        verify(stateWriter, never())
                .saveSnapshot(eq(PROJECT_ID), eq(ObjectType.TABLE), anyString(), any());
    }

    // --- data phase ---------------------------------------------------------------------

    private void stubSuccessfulDataPhase() throws Exception {
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        when(stateWriter.loadOrCreateProgress(PROJECT_ID, "ORDERS")).thenReturn(progress);
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(structureSync.apply(any(),
                argThat(e -> e != null && e.getObjectType() == ObjectType.TABLE), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Table already present: ORDERS"));
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.TableSyncResult());
    }

    @Test
    void theDataPhaseDoesNotRefreshABlockedTablesSnapshot() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of(columnAlter()));
        when(structureSync.apply(any(),
                argThat(e -> e != null && e.getObjectType() == ObjectType.COLUMN), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.failed("boom"));
        stubSuccessfulDataPhase();

        engine.runCycle(ctx(true, true));

        // Data moved fine, but the failed structural change must stay detectable: the
        // end-of-table refresh may not launder the withheld snapshot back in.
        verify(stateWriter, never()).saveSnapshot(anyLong(), any(), anyString(), any());
    }

    @Test
    void theDataPhaseDoesNotRefreshSnapshotsWhileStructureSyncIsOff() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        stubSuccessfulDataPhase();

        engine.runCycle(ctx(false, true));

        // Refreshing while structure sync is off would mark every pending source-side
        // structural change as seen, so turning structure sync on later would apply none
        // of them.
        verify(stateWriter, never()).saveSnapshot(anyLong(), any(), anyString(), any());
    }

    // --- lock lease ----------------------------------------------------------------------

    @Test
    void aLostLockAbortsTheCycleBeforeTheNextTable() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        stubSuccessfulDataPhase();

        SyncResult result = engine.runCycle(ctx(false, true), () -> false);

        assertThat(result.isSuccess()).isFalse();
        verify(dataSync, never()).syncTable(any(), any(), any(), any(), any(), any());
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.PROJECT), eq("demo"),
                eq(ChangeType.ERROR), contains("Sync lock lost"), eq(false), eq(0L));
    }

    @Test
    void aHealthyLockRenewalLetsTheCycleFinish() throws Exception {
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        stubSuccessfulDataPhase();

        SyncResult result = engine.runCycle(ctx(false, true), () -> true);

        assertThat(result.isSuccess()).isTrue();
        verify(dataSync).syncTable(any(), any(), any(), any(), any(), any());
    }
}
