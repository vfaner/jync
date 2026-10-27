package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * A table the data phase auto-creates must build the indexes it deferred, not lose them.
 *
 * <p>With structure sync off, the cycle reads the reduced shape (no index dictionary). When
 * the target table is missing, the data phase re-reads that one table in full so the CREATE
 * carries its indexes and comments — but the deferred non-unique indexes are then built
 * after the initial load from the table the cycle holds. Handing that step the reduced
 * metadata leaves {@code secondaryIndexes()} empty: the indexes are neither created nor
 * queued, and the snapshot records the table as converged.
 */
class SyncEngineAutoCreatedTableIndexTest {

    private static final long PROJECT_ID = 41L;

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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:autocreate_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:autocreate_tgt", "sa", "");
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("auto-create-index");

        TableMeta reduced = table("ORDERS");
        TableMeta full = table("ORDERS");
        IndexMeta idx = new IndexMeta();
        idx.setName("IDX_ORDERS_STATUS");
        idx.getColumns().add("STATUS");
        full.setIndexes(List.of(idx));

        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(reduced));
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);
        when(sourceReader.readTable(sourceConn, "APP", "ORDERS")).thenReturn(full);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        // An empty target listing: ensureTargetTableExists takes the auto-CREATE path.
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of());

        when(structureSync.sortByDependency(any())).thenReturn(List.of(reduced));
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));
        when(structureSync.hasPendingIndexes(PROJECT_ID, "ORDERS")).thenReturn(false);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
        // The auto-CREATE path resets the table's cursor; hand the same instance back as the
        // real writer does so the cycle continues with it.
        when(stateWriter.forceReload(any())).thenAnswer(inv -> inv.getArgument(0));

        DataSyncService.TableSyncResult initialLoad = mock(DataSyncService.TableSyncResult.class);
        when(initialLoad.isInitialLoad()).thenReturn(true);
        when(initialLoad.isSuccess()).thenReturn(true);
        when(initialLoad.isSkipped()).thenReturn(false);
        when(initialLoad.getRowsChanged()).thenReturn(0L);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(initialLoad);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void theDeferredIndexesComeFromTheFullDefinitionTheCreateUsed() {
        engine.runCycle(ctx());

        verify(structureSync).createDeferredIndexes(any(), any(),
                argThat(t -> t != null && "ORDERS".equals(t.getName())
                        && !t.secondaryIndexes().isEmpty()));
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
