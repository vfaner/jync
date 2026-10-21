package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * Which metadata read a cycle uses, and what an auto-CREATE uses when the cycle read the
 * reduced shape.
 *
 * <p>Structure sync off means change detection never runs, so the index/foreign-key/comment
 * dictionaries are dead weight on every poll. The one place the reduced shape is not enough
 * is a target table that has to be built during the data phase: the CREATE must still carry
 * the indexes and comments that structure sync would normally add, so that single table is
 * read in full, on demand.
 */
class SyncEngineMetadataOnDemandTest {

    private MetadataReader reader;
    private SyncEngine engine;
    private Connection sourceConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        ChangeDetector changeDetector = mock(ChangeDetector.class);
        reader = mock(MetadataReader.class);
        engine = new SyncEngine(dataSourceManager, changeDetector,
                mock(CursorStrategyResolver.class), mock(StructureSyncService.class),
                mock(DataSyncService.class), mock(SyncStateWriter.class), new SyncProperties());

        sourceConn = mock(Connection.class);
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(mock(Connection.class));
        when(reader.readAll(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new DatabaseMeta());
        when(reader.readAllForData(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new DatabaseMeta());

        project = new Project();
        project.setId(9L);
        project.setName("on-demand");
    }

    private SyncContext ctx(boolean syncStructure) {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(syncStructure);
        config.setSyncData(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceSchema("APP")
                .sourceReader(reader)
                .targetReader(mock(MetadataReader.class))
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @Test
    void aStructureOffCycleReadsTheReducedShape() throws Exception {
        engine.runCycle(ctx(false));

        verify(reader).readAllForData(any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean());
        verify(reader, never()).readAll(any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean());
    }

    @Test
    void aStructureOnCycleReadsTheFullShape() throws Exception {
        engine.runCycle(ctx(true));

        verify(reader).readAll(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
        verify(reader, never()).readAllForData(any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean());
    }

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setSchema("APP");
        return table;
    }

    @Test
    void aStructureOffCycleReReadsTheOneTableItIsAboutToCreate() throws Exception {
        TableMeta reduced = table("ORDERS");
        TableMeta full = table("ORDERS");
        when(reader.readTable(sourceConn, "APP", "ORDERS")).thenReturn(full);

        assertThat(engine.completeDefinitionForCreate(reduced, sourceConn, ctx(false)))
                .as("the CREATE must carry the indexes and comments a full read has")
                .isSameAs(full);
    }

    @Test
    void aStructureOnCycleCreatesFromTheMetadataItAlreadyRead() throws Exception {
        TableMeta full = table("ORDERS");

        assertThat(engine.completeDefinitionForCreate(full, sourceConn, ctx(true)))
                .isSameAs(full);
        verify(reader, never()).readTable(any(), any(), any());
    }

    @Test
    void aFailedReReadFallsBackToTheReducedDefinition() throws Exception {
        TableMeta reduced = table("ORDERS");
        when(reader.readTable(sourceConn, "APP", "ORDERS"))
                .thenThrow(new SQLException("dictionary unavailable"));

        assertThat(engine.completeDefinitionForCreate(reduced, sourceConn, ctx(false)))
                .as("a failed re-read must not stop the table from being created")
                .isSameAs(reduced);
    }
}
