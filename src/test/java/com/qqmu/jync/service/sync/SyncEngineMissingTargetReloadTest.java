package com.qqmu.jync.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ChangeEvent;
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
 * A target table Jync recreates because it vanished must be reloaded from the beginning.
 *
 * <p>The stored cursor is a claim about a target that no longer exists: every row below the
 * watermark is missing from the new table, and an incremental window never looks below it.
 * The pass must therefore start over as a full load — which is also what makes its rows file
 * as inserts instead of falling into the phase heuristic and being reported as updates.
 */
class SyncEngineMissingTargetReloadTest {

    private static final long PROJECT_ID = 42L;

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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:missing_target_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:missing_target_tgt", "sa", "");
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("missing-target-reload");

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

        progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("42");
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString())).thenReturn(progress);
        when(stateWriter.forceReload(progress)).thenAnswer(inv -> {
            SyncProgress cleared = inv.getArgument(0);
            cleared.setLastSyncValue(null);
            cleared.setInitialLoadDone(false);
            return cleared;
        });

        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isInitialLoad()).thenReturn(true);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0L);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(result);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void aRecreatedTargetTableStartsTheLoadOverInsteadOfResumingAboveTheOldCursor()
            throws Exception {
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of());
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));

        engine.runCycle(ctx());

        verify(stateWriter).forceReload(progress);
        ArgumentCaptor<SyncProgress> handedToLoad = ArgumentCaptor.forClass(SyncProgress.class);
        verify(dataSync).syncTable(any(), any(), any(), any(), handedToLoad.capture(), any());
        assertNull(handedToLoad.getValue().getLastSyncValue(),
                "the data phase must see no cursor so it performs a full load");
        assertFalse(handedToLoad.getValue().getInitialLoadDone(),
                "a table with no incremental strategy is skipped while initialLoadDone is set");
    }

    /**
     * The user-visible half of the fix: rows that the target could only have received as
     * inserts must be filed as inserts, not as revisions. The data service decides "first
     * load" from the cursor, so the mock mirrors that contract here.
     */
    @Test
    void aReloadedTableIsFiledAsInsertedNotUpdated() throws Exception {
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of());
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            SyncProgress handed = inv.getArgument(4);
            DataSyncService.TableSyncResult load = mock(DataSyncService.TableSyncResult.class);
            when(load.isInitialLoad()).thenReturn(handed.getLastSyncValue() == null);
            when(load.isSuccess()).thenReturn(true);
            when(load.isSkipped()).thenReturn(false);
            when(load.getRowsChanged()).thenReturn(5L);
            // A rewritten MySQL batch reports no per-row kind: all five arrive unclassified.
            when(load.getRowsInserted()).thenReturn(0L);
            when(load.getRowsUpdated()).thenReturn(0L);
            when(load.getRowsChangedUnclassified()).thenReturn(5L);
            when(load.getRowsWritten()).thenReturn(5L);
            return load;
        });

        engine.runCycle(ctx());

        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA), eq("ORDERS"),
                eq(ChangeType.INSERT),
                argThat(m -> m != null && m.contains("row(s) inserted")),
                eq(true), anyLong(), eq(5));
        verify(stateWriter, never()).recordLog(any(), any(), any(), eq(ChangeType.UPDATE),
                any(), anyBoolean(), anyLong(), any());
    }

    /**
     * The same reload rule when the structure phase, not the data phase, is what rebuilt the
     * table. The data phase reads the target listing after structure sync ran, so the table is
     * there and looks like any other pre-existing table — but the stored cursor still describes
     * a table that no longer exists.
     */
    @Test
    void aTableCreatedByTheStructurePhaseIsAlsoReloadedFromScratch() throws Exception {
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT)");
        }
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));
        TableMeta orders = table("ORDERS");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of(
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "ORDERS",
                        "Missing on the target", orders)));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));

        engine.runCycle(ctx(true));

        verify(stateWriter).forceReload(progress);
        ArgumentCaptor<SyncProgress> handedToLoad = ArgumentCaptor.forClass(SyncProgress.class);
        verify(dataSync).syncTable(any(), any(), any(), any(), handedToLoad.capture(), any());
        assertNull(handedToLoad.getValue().getLastSyncValue(),
                "the data phase must not resume above the old cursor of a rebuilt table");
    }

    /**
     * The reset must happen when the table is built, not when the data phase gets to it: a
     * cycle that stops between the phases (lease lost, stop requested) or runs structure only
     * saves the snapshot, so the CREATE is never re-detected — and the data phase of a later
     * cycle would happily resume above the old cursor of a table that now holds nothing.
     */
    @Test
    void aTableCreatedByTheStructurePhaseResetsItsProgressEvenWithoutADataPhase() throws Exception {
        TableMeta orders = table("ORDERS");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of(
                ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "ORDERS",
                        "Missing on the target", orders)));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));

        engine.runCycle(ctx(true, false));

        verify(stateWriter).forceReload(progress);
        verify(dataSync, never()).syncTable(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anExistingTargetTableKeepsItsCursor() throws Exception {
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT NOT NULL PRIMARY KEY)");
        }
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));

        engine.runCycle(ctx());

        verify(structureSync, never()).apply(any(), any(), any());
        verify(stateWriter, never()).forceReload(any());
        ArgumentCaptor<SyncProgress> handedToLoad = ArgumentCaptor.forClass(SyncProgress.class);
        verify(dataSync).syncTable(any(), any(), any(), any(), handedToLoad.capture(), any());
        assertEquals("42", handedToLoad.getValue().getLastSyncValue(),
                "a table that was already there must not be reloaded from scratch");
    }

    private SyncContext ctx() {
        return ctx(false);
    }

    private SyncContext ctx(boolean syncStructure) {
        return ctx(syncStructure, true);
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
