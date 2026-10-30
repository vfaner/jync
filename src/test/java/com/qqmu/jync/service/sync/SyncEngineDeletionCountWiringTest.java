package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.GenericMetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * The per-table result handed to {@code advanceCursor} must carry this cycle's deletion count.
 *
 * <p>{@link SyncStateWriter} treats a delete-only cycle as a delivered change ("deletes
 * included; getRowsChanged() omits them"), so a table whose only change was a source-side
 * delete still moves its 上次同步. That branch reads the <em>per-table</em> result — the
 * engine folds the deletion count into the project-wide {@code SyncResult} only, so unless
 * it is also copied onto the table result the timestamp stays frozen on exactly the cycles
 * the rule exists for.
 *
 * <p>The harness is real: H2 on both ends, the real {@link DataSyncService} doing the
 * delete detection, and only the state writer mocked — the assertion is on the object the
 * engine actually passes it.
 */
class SyncEngineDeletionCountWiringTest {

    private static final String SOURCE_URL = "jdbc:h2:mem:del_count_src;DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL = "jdbc:h2:mem:del_count_tgt;DB_CLOSE_DELAY=-1";

    private DataSourceManager dataSourceManager;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private Project project;

    @BeforeEach
    void setUp() throws SQLException {
        for (String url : List.of(SOURCE_URL, TARGET_URL)) {
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
                st.execute("CREATE TABLE BIG (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            }
        }
        try (Connection c = DriverManager.getConnection(SOURCE_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("MERGE INTO BIG (id, name) VALUES (1, 'a')");
        }
        // The target holds an extra row the source no longer has: the only change this
        // cycle delivers is that deletion.
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("MERGE INTO BIG (id, name) VALUES (1, 'a')");
            st.execute("MERGE INTO BIG (id, name) VALUES (2, 'gone')");
        }

        SyncProperties properties = new SyncProperties();
        dataSourceManager = mock(DataSourceManager.class);
        stateWriter = mock(SyncStateWriter.class);
        StructureSyncService structureSync = mock(StructureSyncService.class);
        engine = new SyncEngine(dataSourceManager, mock(ChangeDetector.class),
                new CursorStrategyResolver(properties), structureSync,
                new DataSyncService(properties), stateWriter, properties);

        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenAnswer(
                inv -> DriverManager.getConnection(SOURCE_URL, "sa", ""));
        when(dataSourceManager.getConnection(targetCfg)).thenAnswer(
                inv -> DriverManager.getConnection(TARGET_URL, "sa", ""));
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.hasPendingIndexes(anyLong(), anyString())).thenReturn(false);
        when(stateWriter.loadOrCreateProgress(anyLong(), anyString()))
                .thenReturn(new SyncProgress());

        project = new Project();
        project.setId(12L);
        project.setName("del-count");
    }

    @AfterEach
    void tearDown() {
        for (String url : List.of(SOURCE_URL, TARGET_URL)) {
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
                // The engine closes the handles it borrows; a failed test may already have.
            }
        }
    }

    @Test
    @DisplayName("the deletion count reaches the per-table result advanceCursor reads")
    void theDeletionCountReachesThePerTableResult() throws SQLException {
        engine.runCycle(ctx());

        ArgumentCaptor<DataSyncService.TableSyncResult> passed =
                ArgumentCaptor.forClass(DataSyncService.TableSyncResult.class);
        verify(stateWriter).advanceCursor(any(SyncProgress.class), passed.capture(), any());

        // Asserted on its own: the H2 harness re-reads the whole table every cycle and its
        // MERGE reports the unchanged row as a match, so the change count is not the point —
        // the deletion count is, and only the engine can put it here.
        assertThat(passed.getValue().getRowsDeleted())
                .as("advanceCursor's delete branch reads this count; zero freezes 上次同步")
                .isEqualTo(1L);
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.getTables().add("BIG");
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setSyncDeletes(true);
        config.setDisableTargetConstraints(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(new GenericMetadataReader())
                .targetReader(new GenericMetadataReader())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }
}
