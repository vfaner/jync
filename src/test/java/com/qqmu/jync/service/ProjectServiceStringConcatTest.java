package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.projection.redis.RedisProjectionPolicy;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The detail page flags views and routines whose body uses {@code ||} when the target reads
 * it as logical OR.
 *
 * <p>Oracle joins strings with {@code ||}. MySQL and every product in its family (GoldenDB,
 * TDSQL, PolarDB, OceanBase, TiDB …) parse that token as logical OR unless
 * {@code PIPES_AS_CONCAT} is set. The structure sync hands the body through unchanged, so the
 * target view is created successfully and then returns 0/1 instead of the joined text —
 * a wrong answer, not an error. The warning names the objects so the operator can supply a
 * DDL override before the first run.
 *
 * <p>Advisory, like the cursor-index hint: an unreachable source or a missing dictionary
 * costs the warning, not the page.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceStringConcatTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private CdcPositionStore positionStore;
    @Mock private com.qqmu.jync.repository.TaskMetricSampleRepository taskMetricSampleRepository;
    @Mock private com.qqmu.jync.repository.AlertStateRepository alertStateRepository;

    private final SyncProperties properties = new SyncProperties();

    private ProjectService service;
    private Project project;
    private DatabaseConfig source;
    private DatabaseConfig target;
    private SyncConfig config;
    private MetadataReader reader;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                taskMetricSampleRepository, alertStateRepository, properties,
                mock(RedisProjectionPolicy.class));

        project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setSourceDbId(100L);
        project.setTargetDbId(200L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        source = new DatabaseConfig();
        source.setId(100L);
        source.setName("src");
        source.setType(DatabaseType.ORACLE);
        source.setSchemaName("APP");
        lenient().when(databaseConfigRepository.findById(100L)).thenReturn(Optional.of(source));

        target = new DatabaseConfig();
        target.setId(200L);
        target.setName("dst");
        target.setType(DatabaseType.GOLDENDB);
        lenient().when(databaseConfigRepository.findById(200L)).thenReturn(Optional.of(target));

        config = new SyncConfig();
        lenient().when(contextFactory.parseConfig(project)).thenReturn(config);

        reader = mock(MetadataReader.class);
        lenient().when(readerFactory.forType(eq(DatabaseType.ORACLE), any())).thenReturn(reader);
        connection = mock(Connection.class);
        lenient().when(dataSourceManager.getConnection(source)).thenReturn(connection);
    }

    @Test
    void flagsTheViewsAndRoutinesThatUsePipes() throws SQLException {
        when(reader.listViewNames(connection, "APP")).thenReturn(List.of("ORDERS_V", "PLAIN_V"));
        when(reader.readView(connection, "APP", "ORDERS_V")).thenReturn(view(
                "SELECT first_name || ' ' || last_name AS full_name FROM users"));
        when(reader.readView(connection, "APP", "PLAIN_V")).thenReturn(view(
                "SELECT first_name, last_name FROM users"));
        when(reader.listProcedureNames(connection, "APP")).thenReturn(List.of("GREET"));
        when(reader.readProcedure(connection, "APP", "GREET")).thenReturn(procedure(
                "BEGIN v := 'hi ' || name; END"));

        assertThat(service.findStringConcatenationWarnings(1L))
                .extracting(ProjectService.StringConcatWarning::getObjectType,
                        ProjectService.StringConcatWarning::getObjectName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ObjectType.VIEW, "ORDERS_V"),
                        org.assertj.core.groups.Tuple.tuple(ObjectType.PROCEDURE, "GREET"));
    }

    @Test
    void pipesInsideLiteralsAreNotFlagged() throws SQLException {
        when(reader.listViewNames(connection, "APP")).thenReturn(List.of("NOTE_V"));
        when(reader.readView(connection, "APP", "NOTE_V")).thenReturn(view(
                "SELECT 'a || b' AS note FROM t"));
        lenient().when(reader.listProcedureNames(connection, "APP")).thenReturn(List.of());

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
    }

    @Test
    void aSourceThatAlsoReadsPipesAsOrIsNotWarnedAbout() {
        source.setType(DatabaseType.GOLDENDB);

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
        verifyNoInteractions(dataSourceManager);
    }

    @Test
    void aNonMysqlTargetIsNotWarnedAbout() {
        target.setType(DatabaseType.POSTGRESQL);

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
        verifyNoInteractions(dataSourceManager);
    }

    @Test
    void switchingViewsAndRoutinesOffSkipsTheInspection() {
        config.setSyncViews(false);
        config.setSyncProcedures(false);

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
        verifyNoInteractions(dataSourceManager);
    }

    @Test
    void onlySelectedObjectsAreInspected() throws SQLException {
        config.setObjectSelectionExplicit(true);
        config.setViews(Set.of("ORDERS_V"));
        config.setProcedures(Set.of());
        when(reader.listViewNames(connection, "APP")).thenReturn(List.of("ORDERS_V", "PLAIN_V"));
        when(reader.readView(connection, "APP", "ORDERS_V")).thenReturn(view(
                "SELECT a || b AS c FROM t"));

        assertThat(service.findStringConcatenationWarnings(1L))
                .extracting(ProjectService.StringConcatWarning::getObjectName)
                .containsExactly("ORDERS_V");
        verify(reader, never()).readView(any(Connection.class), eq("APP"), eq("PLAIN_V"));
        // An explicitly empty routine selection means "none of this kind": no listing, no read.
        verify(reader, never()).listProcedureNames(any(Connection.class), eq("APP"));
    }

    @Test
    void anExistingOverrideCountsAsReviewed() throws SQLException {
        config.getDdlOverrides().put("VIEW:ORDERS_V",
                "CREATE OR REPLACE VIEW ORDERS_V AS SELECT CONCAT(a, b) AS c FROM t");
        when(reader.listViewNames(connection, "APP")).thenReturn(List.of("ORDERS_V"));
        lenient().when(reader.listProcedureNames(connection, "APP")).thenReturn(List.of());

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
        verify(reader, never()).readView(any(Connection.class), eq("APP"), eq("ORDERS_V"));
    }

    @Test
    void anUnreachableSourceYieldsNoWarnings() throws SQLException {
        when(dataSourceManager.getConnection(source))
                .thenThrow(new IllegalStateException("error.connection.failed"));

        assertThat(service.findStringConcatenationWarnings(1L)).isEmpty();
    }

    private static ViewMeta view(String definition) {
        ViewMeta meta = new ViewMeta();
        meta.setDefinition(definition);
        return meta;
    }

    private static ProcedureMeta procedure(String definition) {
        ProcedureMeta meta = new ProcedureMeta();
        meta.setDefinition(definition);
        return meta;
    }
}
