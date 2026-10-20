package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * How the object selection reaches the metadata reader.
 *
 * <p>"Empty set" used to have exactly one meaning inside the engine — sync everything. Now
 * the config carries an explicit-selection flag, and only a legacy config keeps that
 * reading; an explicitly empty selection must be handed to the reader as empty lists so the
 * cycle reads nothing and finishes as a no-op.
 */
class SyncEngineObjectSelectionTest {

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private MetadataReader reader;
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
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        reader = mock(MetadataReader.class);
        engine = new SyncEngine(dataSourceManager, changeDetector,
                mock(CursorStrategyResolver.class), structureSync,
                dataSync, mock(SyncStateWriter.class), new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:objsel_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:objsel_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);
        when(reader.readAll(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(new DatabaseMeta());
        // Un-stubbed List returns are null here, and the engine iterates them.
        when(structureSync.retryPendingIndexes(any(), any())).thenReturn(List.of());
        when(structureSync.sortByDependency(any())).thenReturn(List.of());
        when(changeDetector.detect(anyLong(), any(), any())).thenReturn(List.of());

        project = new Project();
        project.setId(9L);
        project.setName("selection");
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    /** A cycle that skips structure and data: the only observable work is the metadata read. */
    private SyncContext ctx() {
        return ctx(false, false);
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
                .targetReader(mock(MetadataReader.class))
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @Test
    void explicitEmptySelectionAsksTheReaderForNoObjects() throws Exception {
        SyncContext ctx = ctx();
        ctx.getConfig().setObjectSelectionExplicit(true);

        engine.runCycle(ctx);

        verify(reader).readAll(any(), any(),
                argThat(l -> l != null && l.isEmpty()),
                argThat(l -> l != null && l.isEmpty()),
                argThat(l -> l != null && l.isEmpty()),
                anyBoolean(), anyBoolean());
    }

    @Test
    void legacyEmptySelectionStillReadsEveryObject() throws Exception {
        engine.runCycle(ctx());

        verify(reader).readAll(any(), any(),
                isNull(), isNull(), isNull(), anyBoolean(), anyBoolean());
    }

    @Test
    void anExplicitlyEmptySelectionFinishesAsANoOpCycle() throws Exception {
        // The user's requirement: structure + data on, nothing selected — the cycle must
        // finish cleanly with nothing applied instead of failing or syncing everything.
        SyncContext ctx = ctx(true, true);
        ctx.getConfig().setObjectSelectionExplicit(true);
        when(changeDetector.detect(anyLong(), any(), any())).thenReturn(List.of());
        when(structureSync.sortByDependency(any())).thenReturn(List.of());

        SyncResult result = engine.runCycle(ctx);

        assertThat(result.isSuccess()).isTrue();
        verify(structureSync, never()).apply(any(), any(), any());
        verify(structureSync, never()).retryPendingIndexes(any(), any());
    }
}
