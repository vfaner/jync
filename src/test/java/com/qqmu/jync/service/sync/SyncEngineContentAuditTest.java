package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
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
 * Wiring contract of the content audit: it runs once per successfully committed table, after
 * the cursor has moved, and it can never change the cycle's outcome.
 */
class SyncEngineContentAuditTest {

    private static final long PROJECT_ID = 7L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private ContentAuditService contentAudit;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;
    private SyncProgress progress;
    private CursorStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "");
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        contentAudit = mock(ContentAuditService.class);
        sourceReader = mock(MetadataReader.class);
        targetReader = mock(MetadataReader.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties(), contentAudit);

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:ca_engine_src_" + tag, "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:ca_engine_tgt_" + tag, "sa", "");
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT NOT NULL PRIMARY KEY)");
        }
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("content-audit");

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
        strategy = CursorStrategy.identity("ID", Types.BIGINT, "test");
        when(cursorResolver.resolve(any(), any())).thenReturn(strategy);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));

        progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("42");
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString())).thenReturn(progress);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void runsTheAuditAfterTheCursorAdvances() throws Exception {
        stubLoad(true, false, 1L);
        when(contentAudit.maybeAudit(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ok());

        engine.runCycle(ctx());

        InOrder order = inOrder(stateWriter, contentAudit);
        order.verify(stateWriter).advanceCursor(eq(progress), any(), eq(strategy));
        order.verify(contentAudit).maybeAudit(any(), any(), any(), any(), any(), any(), any());
        order.verify(contentAudit).record(any(), any());
    }

    @Test
    void doesNotAuditWhenTheTableFailed() throws Exception {
        stubLoad(false, false, 0L);

        engine.runCycle(ctx());

        verify(contentAudit, never()).maybeAudit(any(), any(), any(), any(), any(), any(), any());
        verify(contentAudit, never()).record(any(), any());
    }

    @Test
    void doesNotAuditWhenTheTableWasSkipped() throws Exception {
        stubLoad(true, true, 0L);

        engine.runCycle(ctx());

        verify(contentAudit, never()).maybeAudit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createsOneBudgetPerCycleAndHandsItToEveryAudit() throws Exception {
        stubLoad(true, false, 1L);
        ContentAuditService.CycleBudget budget = new ContentAuditService.CycleBudget(1);
        when(contentAudit.newCycleBudget()).thenReturn(budget);

        engine.runCycle(ctx());

        ArgumentCaptor<ContentAuditService.CycleBudget> passed =
                ArgumentCaptor.forClass(ContentAuditService.CycleBudget.class);
        verify(contentAudit).maybeAudit(any(), any(), any(), any(), any(), any(),
                passed.capture());
        assertThat(passed.getValue()).isSameAs(budget);
        verify(contentAudit, times(1)).newCycleBudget();
    }

    @Test
    void anAuditFailureLeavesTheCycleSuccessful() throws Exception {
        stubLoad(true, false, 1L);
        when(contentAudit.maybeAudit(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit exploded"));

        SyncResult result = engine.runCycle(ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTablesProcessed()).isEqualTo(1);
    }

    @Test
    void evictingTheProjectsCachesAlsoDropsItsAuditState() {
        engine.evictProjectCaches(PROJECT_ID);

        verify(contentAudit).evictProject(PROJECT_ID);
    }

    private static TableContentAudit ok() {
        return new TableContentAudit("ORDERS", TableContentAudit.Status.OK, 1, 1, 0, 0, 0,
                List.of(), null, 1L);
    }

    private void stubLoad(boolean success, boolean skipped, long inserted) {
        DataSyncService.TableSyncResult load = mock(DataSyncService.TableSyncResult.class);
        when(load.isInitialLoad()).thenReturn(true);
        when(load.isSuccess()).thenReturn(success);
        when(load.isSkipped()).thenReturn(skipped);
        when(load.getSkipReason()).thenReturn("skipped");
        when(load.getError()).thenReturn("failed");
        when(load.getRowsChanged()).thenReturn(inserted);
        when(load.getRowsInserted()).thenReturn(inserted);
        when(load.getRowsUpdated()).thenReturn(0L);
        when(load.getRowsChangedUnclassified()).thenReturn(0L);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(load);
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncData(true);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncDeletes(false);
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
