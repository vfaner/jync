package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncTaskRepository;
import com.qqmu.jync.service.connection.PoolContentionException;
import com.qqmu.jync.service.metrics.MetricsSampler;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * A deferred round must leave the ERROR hat on. It ran nothing, so it may not clear the park:
 * {@code CdcConsumerManager.hasErrorHat} reads the status, and a round that starts then defers
 * would otherwise restart a consumer for a project a fatal failure had parked.
 */
class SyncTaskRunnerErrorHatTest {

    private ProjectRepository projectRepository;
    private SyncContextFactory contextFactory;
    private SyncEngine syncEngine;
    private SyncTaskRunner runner;
    private SyncTask task;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        SyncTaskRepository taskRepository = mock(SyncTaskRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        SyncProperties properties = new SyncProperties();
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                new SyncLockService(lockStore, properties, 8080),
                new SyncTaskStore(taskRepository, properties), new PoolActivity(properties),
                mock(MetricsSampler.class));

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.build(any(), anyString(), any(), any())).thenReturn(
                SyncContext.builder().project(project).config(new SyncConfig())
                        .sourceConfig(new DatabaseConfig()).targetConfig(new DatabaseConfig())
                        .build());

        task = new SyncTask();
        task.setProjectId(1L);
        task.setStatus(TaskStatus.ERROR);
        task.setConsecutiveFailures(properties.getMaxRetries());
        task.setLastSyncResult("SERVER_ID_CONFLICT");
        when(taskRepository.findByProjectId(1L)).thenReturn(Optional.of(task));

        when(syncEngine.runCycle(any())).thenThrow(new PoolContentionException("pool busy"));
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    @Test
    void aDeferredRoundLeavesTheErrorHatOn() {
        runner.runOnce(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.ERROR);
        assertThat(task.getLastSyncResult()).isEqualTo("SERVER_ID_CONFLICT");
    }
}
