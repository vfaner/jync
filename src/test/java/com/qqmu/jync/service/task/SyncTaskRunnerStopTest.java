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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * Stopping a project must reach the cycle that is already running, not just the schedule.
 *
 * <p>Without that request the in-flight round keeps its lease (a long full load renews it for
 * minutes), every config save is still refused with "stop the sync first" — and the user has
 * just done exactly that. The request is delivered through the context, so the cycle aborts at
 * its next committed batch instead of being killed mid-write.
 */
class SyncTaskRunnerStopTest {

    private ProjectRepository projectRepository;
    private SyncContextFactory contextFactory;
    private SyncEngine syncEngine;
    private SyncTaskStore taskStore;
    private SyncTaskRunner runner;

    private final AtomicReference<BooleanSupplier> stopSignal = new AtomicReference<>();
    private CountDownLatch cycleEntered;
    private CountDownLatch holdCycle;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        taskStore = mock(SyncTaskStore.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        SyncLockService lockService = new SyncLockService(lockStore, new SyncProperties(), 8080);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                lockService, taskStore, new PoolActivity(new SyncProperties()));

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        when(contextFactory.build(any(), anyString(), any(), any())).thenAnswer(inv -> {
            BooleanSupplier requested = inv.getArgument(3);
            stopSignal.set(requested);
            return SyncContext.builder().project(project)
                    .stopRequested(requested).build();
        });
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    /** Engine stub that signals entry, then waits until the test releases it. */
    private void stubHeldCycle() {
        cycleEntered = new CountDownLatch(1);
        holdCycle = new CountDownLatch(1);
        when(syncEngine.runCycle(any())).thenAnswer(inv -> {
            cycleEntered.countDown();
            holdCycle.await(5, TimeUnit.SECONDS);
            return new SyncResult();
        });
    }

    private void runOnceInBackground() {
        Thread thread = new Thread(() -> runner.runOnce(1L), "test-cycle");
        thread.setDaemon(true);
        thread.start();
    }

    @Test
    @DisplayName("a stop requested while a cycle runs is visible to that cycle's context")
    void aStopDuringACycleReachesItsContext() throws Exception {
        stubHeldCycle();
        runOnceInBackground();
        assertThat(cycleEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(stopSignal.get().getAsBoolean()).isFalse();
        runner.requestStop(1L);
        assertThat(stopSignal.get().getAsBoolean())
                .as("the running cycle must see the stop at its next safe point")
                .isTrue();

        holdCycle.countDown();
    }

    @Test
    @DisplayName("a cycle that starts after the stop is a new run and is not aborted")
    void aCycleStartedAfterTheStopIsNotAborted() {
        runner.requestStop(1L);
        when(syncEngine.runCycle(any())).thenReturn(new SyncResult());

        runner.runOnce(1L);

        assertThat(stopSignal.get().getAsBoolean())
                .as("the stop that predates this run belongs to the previous one")
                .isFalse();
    }

    @Test
    @DisplayName("a cycle aborted by a stop is recorded as stopped, not as a failure")
    void aStoppedCycleIsNotRecordedAsAFailure() {
        when(syncEngine.runCycle(any())).thenAnswer(inv -> {
            // What happens at the next batch boundary once the user pressed stop.
            runner.requestStop(1L);
            SyncResult stopped = new SyncResult();
            stopped.addError("Sync of project 'p' stopped by request");
            return stopped;
        });

        SyncTaskRunner.Outcome outcome = runner.runOnce(1L);

        verify(taskStore).recordStoppedOutcome(eq(1L), any());
        verify(taskStore, never()).recordOutcome(anyLong(), any());
        // The flag is what the HTTP layer reads to say "stopped", not "failed".
        assertThat(outcome.result().orElseThrow().isStopped()).isTrue();
    }
}
