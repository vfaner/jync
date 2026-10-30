package com.qqmu.jync.service.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
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
 * A row-count repair is one event, so it gets one audit entry.
 *
 * <p>A row written directly at the source with an {@code UPDATE_TIME} at or below the stored
 * cursor is invisible to the incremental window, and the row-count audit is what catches it.
 * The repair used to be filed twice — the audit's own INFO entry, then the "Initial load: 1
 * row(s) inserted" entry the reload wrote a millisecond later — so a one-row backfill produced
 * two log lines that describe a single repair. The count evidence now rides along with the
 * entry that reports the reload; the standalone entry remains for cycles where the reload has
 * nothing to report, so the reason a cursor vanished is never lost.
 */
class SyncEngineRowCountAuditLogTest {

    private static final long PROJECT_ID = 7L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;
    private SyncProgress progress;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        sourceReader = mock(MetadataReader.class);
        targetReader = mock(MetadataReader.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:audit_log_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:audit_log_tgt", "sa", "");
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT NOT NULL PRIMARY KEY)");
        }
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("audit-log");

        TableMeta orders = table("ORDERS");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders));
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);

        when(structureSync.sortByDependency(any())).thenReturn(List.of(orders));
        when(structureSync.hasPendingIndexes(PROJECT_ID, "ORDERS")).thenReturn(false);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));

        progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("42");
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString())).thenReturn(progress);
        // Mirrors the real contract: the cursor is nulled on this instance and handed back.
        when(stateWriter.clearCursor(progress)).thenAnswer(inv -> {
            SyncProgress cleared = inv.getArgument(0);
            cleared.setLastSyncValue(null);
            return cleared;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void aRepairsCountsAreFoldedIntoTheEntryThatReportsTheReload() throws Exception {
        repairFound("1");

        engine.runCycle(ctx());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(stateWriter, times(1)).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA),
                eq("ORDERS"), eq(ChangeType.INSERT), message.capture(), eq(true), anyLong(), any());
        String logged = message.getValue();
        assertTrue(logged.contains("1 row(s) inserted"),
                "the entry still reports the reload: " + logged);
        assertTrue(logged.toLowerCase(Locale.ROOT).contains("row count audit"),
                "and now also says why it reloaded: " + logged);
        assertTrue(logged.contains("source 456 vs target 455"),
                "with the counts that justified it: " + logged);
        verify(stateWriter, never()).recordLog(any(), any(), any(), eq(ChangeType.INFO),
                any(), anyBoolean(), anyLong(), any());
    }

    @Test
    void aRepairWhoseReloadChangedNothingIsStillReportedOnItsOwn() throws Exception {
        repairFound("0");

        engine.runCycle(ctx());

        verify(stateWriter, times(1)).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA),
                eq("ORDERS"), eq(ChangeType.INFO),
                org.mockito.ArgumentMatchers.argThat(
                        m -> m != null && m.contains("Cursor cleared")),
                eq(true), anyLong(), any());
    }

    @Test
    void aRepairIsNotLostWhenTheDataPhaseSkipsTheTable() throws Exception {
        repairFound("0");
        DataSyncService.TableSyncResult skipped = mock(DataSyncService.TableSyncResult.class);
        when(skipped.isSkipped()).thenReturn(true);
        when(skipped.getSkipReason()).thenReturn("target is missing a primary key");
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(skipped);

        engine.runCycle(ctx());

        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA), eq("ORDERS"),
                eq(ChangeType.INFO),
                org.mockito.ArgumentMatchers.argThat(
                        m -> m != null && m.contains("Cursor cleared")),
                eq(true), anyLong(), any());
    }

    @Test
    void aTargetedRepairKeepsTheCursorAndFilesOneEntry() throws Exception {
        // The drift was small enough to patch by key, so there is no reload to fold the
        // counts into: the repair files its own entry, the cursor survives, and the cycle
        // continues incrementally instead of re-reading the table.
        when(dataSync.auditSyncedRowCounts(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.RowCountAudit(456, 455));
        when(dataSync.repairMissingRows(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(DataSyncService.RowDriftRepair.repaired(1));
        stubLoad(0);

        engine.runCycle(ctx());

        verify(stateWriter, never()).clearCursor(any(SyncProgress.class));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(stateWriter, times(1)).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA),
                eq("ORDERS"), eq(ChangeType.INFO), message.capture(), eq(true), anyLong(), any());
        String logged = message.getValue();
        assertTrue(logged.toLowerCase(Locale.ROOT).contains("row count audit"), logged);
        assertTrue(logged.contains("1 row(s)"), logged);
        assertFalse(logged.contains("Cursor cleared"),
                "a targeted repair clears nothing: " + logged);
    }

    @Test
    void aRepairTheTargetedWalkDeclinesFallsBackToClearingTheCursor() throws Exception {
        when(dataSync.auditSyncedRowCounts(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.RowCountAudit(456, 455));
        when(dataSync.repairMissingRows(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(DataSyncService.RowDriftRepair.unhandled("table has no primary key"));
        stubLoad(0);

        engine.runCycle(ctx());

        verify(stateWriter, times(1)).clearCursor(progress);
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA), eq("ORDERS"),
                eq(ChangeType.INFO),
                org.mockito.ArgumentMatchers.argThat(
                        m -> m != null && m.contains("Cursor cleared")),
                eq(true), anyLong(), any());
    }

    @Test
    void anOrdinaryLoadCarriesNoAuditText() throws Exception {
        when(dataSync.auditSyncedRowCounts(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        stubLoad(1L);

        engine.runCycle(ctx());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(stateWriter, times(1)).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA),
                eq("ORDERS"), eq(ChangeType.INSERT), message.capture(), eq(true), anyLong(), any());
        assertFalse(message.getValue().toLowerCase(Locale.ROOT).contains("row count audit"),
                "nothing was repaired, so nothing mentions the audit: " + message.getValue());
    }

    /**
     * The target is one row short of what the cursor claims: 456 rows at the source, 455 at the
     * target. The reload then inserts {@code rowsInserted} of them.
     */
    private void repairFound(String rowsInserted) throws SQLException {
        when(dataSync.auditSyncedRowCounts(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.RowCountAudit(456, 455));
        stubLoad(Long.parseLong(rowsInserted));
    }

    private void stubLoad(long inserted) {
        DataSyncService.TableSyncResult load = mock(DataSyncService.TableSyncResult.class);
        when(load.isInitialLoad()).thenReturn(true);
        when(load.isSuccess()).thenReturn(true);
        when(load.isSkipped()).thenReturn(false);
        when(load.getRowsChanged()).thenReturn(inserted);
        when(load.getRowsInserted()).thenReturn(0L);
        when(load.getRowsUpdated()).thenReturn(0L);
        when(load.getRowsChangedUnclassified()).thenReturn(inserted);
        when(load.getRowsWritten()).thenReturn(inserted > 0 ? 526L : 525L);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(load);
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncData(true);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.MYSQL)
                .sourceSchema("APP")
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .deferSecondaryIndexes(true)
                .build();
    }

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        table.setColumns(List.of(id));
        return table;
    }
}
