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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * A "sync now" click that arrives while a cycle is in flight must not be dropped: the running
 * cycle's window was cut when it started, so the click's changes would otherwise wait for the
 * next scheduled poll. Clicks coalesce into a single queued rerun, served on a background
 * thread once the lock frees up — and a quiet button must not spawn any rerun at all.
 */
class SyncTaskRunnerRerunTest {

    private SyncEngine syncEngine;
    private SyncTaskRunner runner;
    private final AtomicInteger cycles = new AtomicInteger();
    private CountDownLatch cycleEntered;

    @BeforeEach
    void setUp() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        SyncContextFactory contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        SyncLockStore lockStore = mock(SyncLockStore.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncLockService lockService = new SyncLockService(lockStore, new SyncProperties(), 8080);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                lockService, taskStore);

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(2L)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    /** Engine stub that counts cycles, signals entry and holds the lock for {@code holdMs}. */
    private void stubEngine(long holdMs) {
        when(syncEngine.runCycle(any(), any())).thenAnswer(invocation -> {
            cycles.incrementAndGet();
            if (cycleEntered != null) {
                cycleEntered.countDown();
            }
            if (holdMs > 0) {
                Thread.sleep(holdMs);
            }
            return new SyncResult();
        });
    }

    private void awaitCycles(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (cycles.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(cycles.get()).isEqualTo(expected);
    }

    @Test
    void clicksDuringACycleCoalesceIntoExactlyOneQueuedRerun() throws Exception {
        cycleEntered = new CountDownLatch(1);
        stubEngine(400);

        AtomicReference<SyncTaskRunner.Outcome> first = new AtomicReference<>();
        Thread running = new Thread(() -> first.set(runner.runOnce(1L)));
        running.start();
        assertThat(cycleEntered.await(3, TimeUnit.SECONDS)).isTrue();

        // Two clicks while the cycle holds the lock: both queued, neither executed here.
        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.QUEUED);
        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.QUEUED);

        running.join(5000);
        assertThat(first.get().kind()).isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);

        // The two clicks become one extra cycle, not two.
        awaitCycles(2);
        Thread.sleep(250);
        assertThat(cycles.get()).isEqualTo(2);
    }

    @Test
    void aClickWithNoCycleInFlightExecutesImmediatelyAndQueuesNothing() throws Exception {
        stubEngine(0);

        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);
        Thread.sleep(250);
        assertThat(cycles.get()).isEqualTo(1);
    }

    @Test
    void aRequestForAMissingProjectRunsNothing() {
        stubEngine(0);

        assertThat(runner.runOnce(2L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.NO_PROJECT);
        verify(syncEngine, never()).runCycle(any(), any());
    }
}
