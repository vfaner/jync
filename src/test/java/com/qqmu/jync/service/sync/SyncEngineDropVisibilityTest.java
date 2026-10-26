package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * The engine must hand change detection the source account's schema visibility, and nothing
 * else may pay for it: a healthy cycle never probes the source.
 *
 * <p>The legacy empty-selection configuration enumerates every object, so a table hidden from
 * the account by a revoked grant simply disappears from the enumeration — the same signal a
 * real drop produces. The probe decides whether that disappearance may delete the target.
 */
class SyncEngineDropVisibilityTest {

    private static final long PROJECT_ID = 7L;
    private static final String SCHEMA = "APP";

    private DataSourceManager dataSourceManager;
    private MetadataReader sourceReader;
    private StructureSyncService structureSync;
    private MetadataSnapshotService snapshotService;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        sourceReader = mock(MetadataReader.class);
        structureSync = mock(StructureSyncService.class);
        snapshotService = mock(MetadataSnapshotService.class);
        // A real detector: the probe wiring is the behavior under test.
        engine = new SyncEngine(dataSourceManager, new ChangeDetector(snapshotService),
                mock(CursorStrategyResolver.class), structureSync, mock(DataSyncService.class),
                mock(SyncStateWriter.class), new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:drop_visibility_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:drop_visibility_tgt", "sa", "");
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("drop-visibility");

        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("applied"));
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void aVanishedTableIsNotDroppedWhenTheAccountCannotSeeTheWholeSchema() throws Exception {
        stubSourceListing(aSurvivorOnly());
        snapshotOf("ORDERS");
        when(sourceReader.schemaListingIsComplete(sourceConn, SCHEMA)).thenReturn(false);

        engine.runCycle(ctx());

        verify(sourceReader).schemaListingIsComplete(sourceConn, SCHEMA);
        verify(structureSync, never()).apply(any(),
                argThat(event -> event.getChangeType() == ChangeType.DROP), any());
    }

    @Test
    void aVanishedTableIsDroppedWhenTheAccountSeesTheWholeSchema() throws Exception {
        stubSourceListing(aSurvivorOnly());
        snapshotOf("ORDERS");
        when(sourceReader.schemaListingIsComplete(sourceConn, SCHEMA)).thenReturn(true);

        engine.runCycle(ctx());

        verify(structureSync).apply(any(),
                argThat(event -> event.getChangeType() == ChangeType.DROP
                        && "ORDERS".equals(event.getObjectName())), any());
    }

    @Test
    void aHealthyCycleNeverProbesTheSource() throws Exception {
        DatabaseMeta healthy = new DatabaseMeta();
        healthy.setTables(List.of(table("ORDERS")));
        stubSourceListing(healthy);
        snapshotOf("ORDERS");

        engine.runCycle(ctx());

        verify(structureSync, never()).apply(any(), any(), any());
        verify(sourceReader, never()).schemaListingIsComplete(any(), anyString());
    }

    private void stubSourceListing(DatabaseMeta meta) throws Exception {
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
    }

    private void snapshotOf(String name) {
        when(snapshotService.loadAll(PROJECT_ID, ObjectType.TABLE, TableMeta.class))
                .thenReturn(Map.of(name, table(name)));
    }

    /** A table that still exists: its presence proves the enumeration itself worked. */
    private static DatabaseMeta aSurvivorOnly() {
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(table("OTHER")));
        return meta;
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

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(true);
        config.setSyncData(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setAllowDrop(true);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.MYSQL)
                .sourceSchema(SCHEMA)
                .sourceReader(sourceReader)
                .targetReader(mock(MetadataReader.class))
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .deferSecondaryIndexes(true)
                .build();
    }
}
