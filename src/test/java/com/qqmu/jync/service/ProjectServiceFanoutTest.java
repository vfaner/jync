package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.projection.redis.RedisProjectionPolicy;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * Enabling extra targets must choose the log strategy the source actually supports. Forcing
 * binlog made every non-MySQL source unstartable; a source with no log engine at all must be
 * refused up front instead of persisting a strategy that can only fail on start.
 */
class ProjectServiceFanoutTest {

    private ProjectRepository projectRepository;
    private DatabaseConfigRepository databaseConfigRepository;
    private SyncContextFactory contextFactory;
    private SyncTaskStore taskStore;
    private CdcPositionStore positionStore;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        databaseConfigRepository = mock(DatabaseConfigRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        taskStore = mock(SyncTaskStore.class);
        positionStore = mock(CdcPositionStore.class);
        service = new ProjectService(projectRepository, databaseConfigRepository,
                mock(SyncProgressRepository.class), mock(ChangeLogRepository.class),
                mock(ChunkCheckpointRepository.class), mock(DataSourceManager.class),
                mock(MetadataReaderFactory.class), mock(MetadataSnapshotService.class),
                contextFactory, mock(SyncScheduler.class), mock(SyncTaskRunner.class),
                taskStore, mock(SyncEngine.class), mock(SyncLockService.class),
                mock(AdminAuditService.class), mock(CdcConsumerManager.class),
                mock(SyncStateWriter.class), positionStore,
                mock(TaskMetricSampleRepository.class), mock(AlertStateRepository.class),
                new SyncProperties(), mock(RedisProjectionPolicy.class));
        when(taskStore.find(anyLong())).thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
    }

    private Project project(DatabaseType sourceType) {
        Project project = new Project();
        project.setId(1L);
        project.setName("fan");
        project.setSourceDbId(5L);
        project.setTargetDbId(2L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        DatabaseConfig source = new DatabaseConfig();
        source.setId(5L);
        source.setName("src");
        source.setType(sourceType);
        when(databaseConfigRepository.findById(5L)).thenReturn(Optional.of(source));
        return project;
    }

    private SyncConfig givenConfig(CaptureStrategy strategy) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        when(contextFactory.parseConfig(any())).thenReturn(config);
        return config;
    }

    private SyncConfig savedConfig() {
        ArgumentCaptor<SyncConfig> captured = ArgumentCaptor.forClass(SyncConfig.class);
        verify(contextFactory).serializeConfig(captured.capture());
        return captured.getValue();
    }

    @Test
    void aPollingMysqlProjectSwitchesToBinlog() {
        project(DatabaseType.MYSQL);
        givenConfig(CaptureStrategy.POLLING);

        service.configureFanout(1L, List.of(3L));

        SyncConfig saved = savedConfig();
        assertThat(saved.getCaptureStrategy()).isEqualTo(CaptureStrategy.BINLOG_CDC);
        assertThat(saved.getFanoutTargetDbIds()).containsExactly(3L);
        verify(positionStore).delete(1L);
    }

    @Test
    void aPollingPostgresProjectSwitchesToItsOwnLogEngine() {
        project(DatabaseType.POSTGRESQL);
        givenConfig(CaptureStrategy.POLLING);

        service.configureFanout(1L, List.of(3L));

        assertThat(savedConfig().getCaptureStrategy())
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void anExistingLogStrategyIsKept() {
        project(DatabaseType.POSTGRESQL);
        givenConfig(CaptureStrategy.PG_LOGICAL_CDC);

        service.configureFanout(1L, List.of(3L));

        assertThat(savedConfig().getCaptureStrategy())
                .isEqualTo(CaptureStrategy.PG_LOGICAL_CDC);
    }

    @Test
    void aSourceWithoutALogEngineIsRefused() {
        project(DatabaseType.MONGODB);
        givenConfig(CaptureStrategy.POLLING);

        assertThatThrownBy(() -> service.configureFanout(1L, List.of(3L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.fanout.requires.binlog");
        verify(positionStore, never()).delete(anyLong());
    }

    @Test
    void anUnchangedTargetListDoesNotResetTheAnchor() {
        Project project = project(DatabaseType.MYSQL);
        project.setSyncConfig("{}");
        SyncConfig config = givenConfig(CaptureStrategy.BINLOG_CDC);
        config.setFanoutTargetDbIds(new java.util.ArrayList<>(List.of(3L)));

        service.configureFanout(1L, List.of(3L));

        verify(positionStore, never()).delete(anyLong());
    }
}
