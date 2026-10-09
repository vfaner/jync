package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
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
    private SyncTaskStore taskStore;
    private SyncLockStore lockStore;
    private ProjectRepository projectRepository;
    private SyncTaskRunner runner;
    private final AtomicInteger cycles = new AtomicInteger();
    private CountDownLatch cycleEntered;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        SyncContextFactory contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        lockStore = mock(SyncLockStore.class);
        taskStore = mock(SyncTaskStore.class);
        SyncLockService lockService = new SyncLockService(lockStore, new SyncProperties(), 8080);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                lockService, taskStore);

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.findById(2L)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    /** Engine stub that counts cycles, signals entry and holds the lock for {@code holdMs}. */
    private void stubEngine(long holdMs) {
        when(syncEngine.runCycle(any())).thenAnswer(invocation -> {
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
        verify(syncEngine, never()).runCycle(any());
    }

    @Test
    void aRecordOutcomeFailureStillDrainsTheQueuedRerun() throws Exception {
        // The finished cycle cannot be recorded (meta DB hiccup); the coalesced request
        // must still be served instead of getting stuck in the queue forever.
        doThrow(new RuntimeException("meta db down"))
                .when(taskStore).recordOutcome(eq(1L), any());
        queuedReruns().add(1L);
        stubEngine(0);

        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);

        awaitCycles(2);
        awaitQueueDrained();
    }

    @Test
    void aMarkRunningFailureIsContainedAndStillDrainsTheQueuedRerun() throws Exception {
        doThrow(new RuntimeException("meta db down"))
                .when(taskStore).markRunning(1L);
        queuedReruns().add(1L);

        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.EXECUTED);

        // The failing cycle itself was not attempted...
        verify(syncEngine, never()).runCycle(any());
        // ...but the bookkeeping failure did not strand the queued extra request.
        awaitQueueDrained();
    }

    @Test
    void aClickWhileAnotherInstanceHoldsTheDbLockSelfServesOnceItFrees() throws Exception {
        // No local holder: the database lock is held by another instance, which will never
        // drain OUR queue. The queued entry must retry on the background pool until the
        // lock frees, then run the cycle itself.
        when(lockStore.acquire(eq(1L), anyString(), anyLong()))
                .thenReturn(false, false, true);
        stubEngine(0);

        assertThat(runner.runOnce(1L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.QUEUED);

        awaitCycles(1);
        awaitQueueDrained();
    }

    @Test
    void aQueuedRerunForAStoppedProjectIsDroppedWithoutACycle() throws Exception {
        // The project was stopped after the click coalesced: no cycle may sneak past stop.
        Project stopped = new Project();
        stopped.setId(3L);
        stopped.setName("stopped");
        stopped.setEnabled(false);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(stopped));
        queuedReruns().add(3L);

        Method runClaimed = SyncTaskRunner.class.getDeclaredMethod(
                "runClaimedRerun", Long.class);
        runClaimed.setAccessible(true);
        runClaimed.invoke(runner, 3L);

        verify(syncEngine, never()).runCycle(any());
        assertThat(queuedReruns()).doesNotContain(3L);
    }

    @Test
    void aStuckOutsideLockStopsAmplifyingAfterTheAttemptCap() throws Exception {
        // Lock always unavailable (dead remote owner within TTL). One click must not turn
        // into an endless retry chain; after the cap the entry is dropped silently.
        when(lockStore.acquire(eq(4L), anyString(), anyLong())).thenReturn(false);
        Project stuck = new Project();
        stuck.setId(4L);
        stuck.setName("stuck");
        stuck.setEnabled(true);
        when(projectRepository.findById(4L)).thenReturn(Optional.of(stuck));

        assertThat(runner.runOnce(4L).kind())
                .isEqualTo(SyncTaskRunner.Outcome.Kind.QUEUED);

        Set<Long> queued = queuedReruns();
        long deadline = System.currentTimeMillis() + 9000;
        while (queued.contains(4L) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(queued).doesNotContain(4L);
        assertThat(cycles.get()).isZero();
    }

    @Test
    void projectExistsReflectsTheRepository() {
        when(projectRepository.existsById(7L)).thenReturn(true);

        assertThat(runner.projectExists(7L)).isTrue();
        assertThat(runner.projectExists(8L)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Set<Long> queuedReruns() throws ReflectiveOperationException {
        Field field = SyncTaskRunner.class.getDeclaredField("queuedReruns");
        field.setAccessible(true);
        return (Set<Long>) field.get(runner);
    }

    private void awaitQueueDrained() throws InterruptedException {
        Set<Long> queued;
        try {
            queued = queuedReruns();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (!queued.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(queued).isEmpty();
    }

    /** The scheduled-fire gate reads the durable flag, never the local schedule. */
    @Test
    void projectEnabledReflectsTheDurableFlag() {
        Project stopped = new Project();
        stopped.setId(3L);
        stopped.setEnabled(false);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(stopped));

        assertThat(runner.projectEnabled(1L)).isTrue();
        assertThat(runner.projectEnabled(3L)).isFalse();
        // A missing row must never answer true: SyncJob checks existence first, but the
        // gate itself stays safe on its own.
        assertThat(runner.projectEnabled(2L)).isFalse();
    }
}
