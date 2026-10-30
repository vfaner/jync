package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.PoolContentionException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * A deferred run leaves the task row alone. Recording it like a normal outcome would stamp
 * "No changes" — the data was never looked at — and reset the failure ladder of a task that
 * has not actually recovered.
 */
class SyncTaskRunnerDeferredTest {

    private ProjectRepository projectRepository;
    private SyncContextFactory contextFactory;
    private SyncEngine syncEngine;
    private SyncTaskStore taskStore;
    private SyncTaskRunner runner;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        taskStore = mock(SyncTaskStore.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                new SyncLockService(lockStore, new SyncProperties(), 8080), taskStore,
                new PoolActivity(new SyncProperties()));

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        when(contextFactory.build(any(), anyString(), any(), any())).thenReturn(
                SyncContext.builder().project(project).config(new SyncConfig())
                        .sourceConfig(source).targetConfig(target).build());
        when(syncEngine.runCycle(any())).thenThrow(new PoolContentionException("pool busy"));
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    @Test
    void aDeferredRunIsReportedAsDeferredAndRecordsNothing() {
        SyncTaskRunner.Outcome outcome = runner.runOnce(1L);

        assertThat(outcome.kind()).isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);
        assertThat(outcome.result()).isPresent();
        assertThat(outcome.result().orElseThrow().isDeferred()).isTrue();
        assertThat(outcome.result().orElseThrow().isSuccess()).isTrue();
        verify(taskStore, never()).recordOutcome(any(), any());
        verify(taskStore, never()).recordStoppedOutcome(any(), any());
    }
}
