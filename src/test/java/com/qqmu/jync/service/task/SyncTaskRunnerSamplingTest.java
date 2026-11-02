package com.qqmu.jync.service.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.qqmu.jync.service.metrics.MetricsSampler;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * The periodic sampler only visits enabled projects. A 立即同步 click on a stopped project
 * still runs a real cycle, so without a sample taken around that run its performance page
 * stays empty no matter how many rows moved.
 */
class SyncTaskRunnerSamplingTest {

    private Project project;
    private PoolActivity poolActivity;
    private MetricsSampler metricsSampler;
    private SyncTaskStore taskStore;
    private SyncEngine syncEngine;
    private SyncTaskRunner runner;

    @BeforeEach
    void setUp() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        SyncContextFactory contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        taskStore = mock(SyncTaskStore.class);
        metricsSampler = mock(MetricsSampler.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        SyncProperties properties = new SyncProperties();
        poolActivity = new PoolActivity(properties);

        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                new SyncLockService(lockStore, properties, 8080), taskStore, poolActivity,
                metricsSampler);

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        project = new Project();
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
    void aManualCycleOnAStoppedProjectIsSampledAtBothEndsOfTheRun() {
        project.setEnabled(false);

        runner.runOnce(1L);

        // One point before the cycle (the baseline to subtract from) and one after it (the
        // rows this run actually moved).
        verify(metricsSampler, times(2)).sampleProject(1L);
    }

    @Test
    void theBaselineIsSampledBeforeTheTaskIsMarkedRunning() {
        // markRunning stamps last_started_at and flips the status to RUNNING. A baseline taken
        // afterwards already carries the new run's start time, so the clock starts a beat late
        // and the gap between "started" and the first real sample is lost. Sample first, then
        // announce the start.
        project.setEnabled(false);

        runner.runOnce(1L);

        var order = inOrder(metricsSampler, taskStore);
        order.verify(metricsSampler).sampleProject(1L);
        order.verify(taskStore).markRunning(1L);
    }

    @Test
    void aFinishedManualRunClosesTheTaskBeforeTakingTheClosingSample() {
        // markRunning left the task RUNNING; without closing it, the closing sample records
        // RUNNING again and a stopped project's page keeps claiming it is syncing.
        project.setEnabled(false);

        runner.runOnce(1L);

        var order = inOrder(taskStore, metricsSampler);
        order.verify(taskStore).markStoppedUnlessError(1L);
        order.verify(metricsSampler).sampleProject(1L);
    }

    @Test
    void aLockLostRunDoesNotCloseTheTask() {
        // The instance that took the lease owns the row now. Writing STOPPED here would
        // overwrite whatever it just recorded.
        project.setEnabled(false);
        SyncResult lost = new SyncResult();
        lost.setLockLost(true);
        when(syncEngine.runCycle(any())).thenReturn(lost);

        runner.runOnce(1L);

        verify(taskStore, never()).markStoppedUnlessError(1L);
        verify(metricsSampler, times(2)).sampleProject(1L);
    }

    @Test
    void anEnabledProjectLeavesSamplingToThePeriodicSampler() {
        // Its cycles fire every poll interval; sampling each one here would multiply the rows
        // written to task_metric_sample many times over. The periodic sampler already visits
        // every enabled project once a minute.
        runner.runOnce(1L);

        verify(metricsSampler, never()).sampleProject(anyLong());
    }

    @Test
    void aCycleDeferredAtAdmissionWritesNoPoint() {
        project.setEnabled(false);
        // Fill both seats of configuration 1 (default pool 5 -> 2 seats) with other cycles.
        poolActivity.tryEnter(1L, 2L);
        poolActivity.tryEnter(1L, 2L);

        runner.runOnce(1L);

        // The round never started, so there is nothing to chart.
        verify(metricsSampler, never()).sampleProject(anyLong());
        verify(syncEngine, never()).runCycle(any());
    }
}
