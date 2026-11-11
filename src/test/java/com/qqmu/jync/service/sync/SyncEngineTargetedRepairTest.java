package com.qqmu.jync.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
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
 * The restricted cycle behind the CDC targeted repair: a dropped target table is rebuilt and
 * reloaded, and nothing else in the project is touched.
 *
 * <p>This is deliberately narrower than a re-baseline. A re-baseline clears every cursor and
 * full-loads every selected table; the repair names the one table whose target vanished, reads
 * only it, skips the structural diff (so unrelated DDL is not consumed into a snapshot it was
 * never applied against), skips deletion detection and the content audit (both describe a
 * window this table no longer has), and still runs the data phase of a log-CDC project because
 * this cycle — unlike a normal poll — is allowed to write rows.
 */
class SyncEngineTargetedRepairTest {

    private static final long PROJECT_ID = 77L;

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
    private TableMeta orders;

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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:repair_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:repair_tgt", "sa", "");
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("targeted-repair");

        orders = table("ORDERS");
        TableMeta customers = table("CUSTOMERS");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders, customers));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(true), eq(true)))
                .thenReturn(meta);
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(true), eq(true)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(true), eq(true))).thenReturn(meta);

        // Identity: the sort must see exactly the tables the engine decided to process, so a
        // table filtered out of the repair set cannot sneak back into the data phase here.
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.apply(any(), any(), any())).thenReturn(
                StructureSyncService.ApplyOutcome.ok("Created table ORDERS (test)"));
        when(structureSync.hasPendingIndexes(PROJECT_ID, "ORDERS")).thenReturn(false);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));

        // An empty target by default: the repair tests that need the table present create it
        // in the real connection and override this listing.
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of());
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
    void repairModeReadsOnlyTheNamedTableAndNeverRunsTheStructuralDiff() throws Exception {
        createTable(targetConn, "ORDERS");
        createTable(targetConn, "CUSTOMERS");
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "CUSTOMERS"));

        engine.runCycle(repairCtx(Set.of("ORDERS")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tables = ArgumentCaptor.forClass(List.class);
        verify(sourceReader).readAll(any(), any(), tables.capture(), any(), any(), eq(true), eq(true));
        assertEquals(List.of("ORDERS"), tables.getValue().stream().sorted().toList(),
                "the repair cycle must read only the table whose target vanished");
        verify(changeDetector, never()).detect(any(), any(), any(), any());
        verify(structureSync, never()).apply(any(), any(), any());
    }

    @Test
    void repairModeRunsTheDataPhaseOfALogCdcProject() throws Exception {
        engine.runCycle(repairCtx(Set.of("ORDERS")));

        // A normal poll of a log-CDC project skips the data phase; this cycle is what rebuilds
        // the lost rows, so it must reach syncTable despite carrying no baseline flag.
        verify(dataSync).syncTable(any(), any(), any(), any(), any(), any());
    }

    @Test
    void repairModeReloadsTheTableEvenWhenTheTargetTableStillExists() throws Exception {
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT NOT NULL PRIMARY KEY)");
        }
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "CUSTOMERS"));

        engine.runCycle(repairCtx(Set.of("ORDERS")));

        verify(stateWriter).forceReload(progress);
        ArgumentCaptor<SyncProgress> handedToLoad = ArgumentCaptor.forClass(SyncProgress.class);
        verify(dataSync).syncTable(any(), any(), any(), any(), handedToLoad.capture(), any());
        assertNull(handedToLoad.getValue().getLastSyncValue(),
                "a repaired table is reloaded from scratch, never resumed above a cursor");
    }

    @Test
    void repairModeSkipsDeletionDetectionAndTheSnapshotRefresh() throws Exception {
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT NOT NULL PRIMARY KEY)");
        }
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "CUSTOMERS"));

        engine.runCycle(repairCtx(Set.of("ORDERS")));

        // The deleted rows this window would compare belong to a target that no longer exists;
        // and the skipped structural diff must not be marked converged in the snapshot.
        verify(dataSync, never()).syncDeletions(any(), any(), any(), any());
        verify(stateWriter, never()).saveSnapshot(any(), any(), any(), any());
    }

    @Test
    void aNormalCdcCycleStillSkipsTheDataPhase() throws Exception {
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "CUSTOMERS"));

        engine.runCycle(baseCtx().toBuilder()
                .cdcBaselineRun(false)
                .build());

        verify(dataSync, never()).syncTable(any(), any(), any(), any(), any(), any());
    }

    private SyncContext repairCtx(Set<String> repairTables) {
        return baseCtx().toBuilder()
                .repairTables(repairTables)
                .cdcRepairRun(true)
                .build();
    }

    private SyncContext baseCtx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(true);
        config.setSyncData(true);
        config.setSyncDeletes(true);
        config.setSyncViews(true);
        config.setSyncProcedures(true);
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
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

    private static void createTable(Connection conn, String name) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + name
                    + " (ID BIGINT NOT NULL PRIMARY KEY)");
        }
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
