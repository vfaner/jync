package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * Deferred secondary indexes must be built after the initial load and before the cursor is
 * advanced: the cursor row is the "this table is done" record, so advancing it first would
 * forever hide a missing index after a crash in that window.
 */
class SyncEngineDeferredIndexTest {

    private static final long PROJECT_ID = 7L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private Connection sourceConn;
    private Connection targetConn;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private TableMeta orders;
    private Project project;

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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:defer_eng_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:defer_eng_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        orders = new TableMeta();
        orders.setName("ORDERS");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        orders.setColumns(List.of(id));

        try (Statement st = targetConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS ORDERS");
            st.execute("CREATE TABLE ORDERS (ID BIGINT)");
        }
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        // Structure sync is off in this fixture, so the engine asks for the reduced read.
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("demo");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(orders));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        SyncProgress progress = new SyncProgress();
        when(stateWriter.loadOrCreateProgress(PROJECT_ID, "ORDERS")).thenReturn(progress);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void deferredIndexesAreBuiltAfterTheInitialLoadAndBeforeTheCursorAdvances() throws Exception {
        DataSyncService.TableSyncResult initialLoad = mock(DataSyncService.TableSyncResult.class);
        when(initialLoad.isInitialLoad()).thenReturn(true);
        when(initialLoad.isSuccess()).thenReturn(true);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(initialLoad);

        engine.runCycle(ctx());

        InOrder order = inOrder(structureSync, stateWriter);
        order.verify(structureSync).createDeferredIndexes(any(), any(), eq(orders));
        order.verify(stateWriter).advanceCursor(any(), eq(initialLoad), any());
    }

    @Test
    void aSkippedTableGetsItsDeferredIndexesBecauseNoLoadWillFollow() throws Exception {
        DataSyncService.TableSyncResult skipped = mock(DataSyncService.TableSyncResult.class);
        when(skipped.isSkipped()).thenReturn(true);
        when(skipped.getSkipReason()).thenReturn("no usable cursor and too many rows");
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(skipped);

        engine.runCycle(ctx());

        // The load will never run, so "build the index after the load" has to be settled now.
        verify(structureSync).createDeferredIndexes(any(), any(), eq(orders));
    }

    @Test
    void aSkipAfterTheInitialLoadCompletedDoesNotProbeForDeferredIndexes() throws Exception {
        SyncProgress loaded = new SyncProgress();
        loaded.setInitialLoadDone(true);
        when(stateWriter.loadOrCreateProgress(PROJECT_ID, "ORDERS")).thenReturn(loaded);
        DataSyncService.TableSyncResult skipped = mock(DataSyncService.TableSyncResult.class);
        when(skipped.isSkipped()).thenReturn(true);
        when(skipped.getSkipReason()).thenReturn("no incremental strategy");
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(skipped);

        engine.runCycle(ctx());

        // Indexes were settled when that load finished; probing with CREATE INDEX on every
        // skipped cycle would issue a failing DDL per index, per cycle, forever.
        verify(structureSync, never()).createDeferredIndexes(any(), any(), any());
    }

    @Test
    void incrementalCyclesLeaveIndexesAlone() throws Exception {
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DataSyncService.TableSyncResult());

        engine.runCycle(ctx());

        verify(structureSync, never()).createDeferredIndexes(any(), any(), any());
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
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .deferSecondaryIndexes(true)
                .build();
    }
}
