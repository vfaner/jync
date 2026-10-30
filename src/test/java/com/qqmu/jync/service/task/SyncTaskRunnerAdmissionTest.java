package com.qqmu.jync.service.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * A fire that would be the third cycle on one connection configuration is deferred at the
 * door: the pool has seats for two, and admitting another would leave the workers nothing.
 * The refusal must cost nothing — no connection attempted, no task row touched.
 */
class SyncTaskRunnerAdmissionTest {

    private ProjectRepository projectRepository;
    private SyncContextFactory contextFactory;
    private SyncEngine syncEngine;
    private SyncTaskStore taskStore;
    private PoolActivity poolActivity;
    private SyncTaskRunner runner;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        taskStore = mock(SyncTaskStore.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        SyncProperties properties = new SyncProperties();
        poolActivity = new PoolActivity(properties);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                new SyncLockService(lockStore, properties, 8080), taskStore, poolActivity);

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        when(contextFactory.build(any(), anyString(), any(), any())).thenReturn(
                SyncContext.builder().project(project).config(new SyncConfig())
                        .sourceConfig(source).targetConfig(target).build());
        when(syncEngine.runCycle(any())).thenReturn(new SyncResult());
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    @Test
    void aCycleBeyondTheConfigurationCeilingIsDeferredBeforeItStarts() {
        // Two other cycles already hold the two seats of config 1 (pool 5 -> 2 seats).
        assertThat(poolActivity.tryEnter(1L, 99L)).isTrue();
        assertThat(poolActivity.tryEnter(1L, 98L)).isTrue();

        SyncTaskRunner.Outcome outcome = runner.runOnce(1L);

        assertThat(outcome.kind()).isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);
        assertThat(outcome.result().orElseThrow().isDeferred()).isTrue();
        verify(syncEngine, never()).runCycle(any());
        verify(taskStore, never()).markRunning(any());
        verify(contextFactory, never()).build(any(), anyString(), any(), any());
        // The foreign seats are untouched: the refusal did not leak a decrement.
        assertThat(poolActivity.activeOn(1L)).isEqualTo(2);
    }

    @Test
    void anAdmittedCycleReleasesItsSeatWhenItFinishes() {
        runner.runOnce(1L);

        assertThat(poolActivity.activeOn(1L)).isZero();
        assertThat(poolActivity.activeOn(2L)).isZero();
    }

    @Test
    void anAdmittedCycleReleasesItsSeatEvenWhenTheRunBlowsUp() {
        when(syncEngine.runCycle(any())).thenThrow(new IllegalStateException("bad config"));

        runner.runOnce(1L);

        assertThat(poolActivity.activeOn(1L)).isZero();
    }
}
