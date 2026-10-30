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
                lockService, taskStore, new PoolActivity(new SyncProperties()));

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
    void aClickDuringARerunIsDrainedByThatRerunToo() throws Exception {
        // The post-cycle drain exists so a click that arrives while a cycle holds the lock is
        // served right after it. A queued rerun runs a cycle too, and a click can land during
        // THAT one — the rerun is then the holder, so it must drain the queue as well.
        // Only runOnce used to drain, so the click stranded in the queue until the project's
        // next natural cycle — a ghost extra cycle later, or nothing at all for a manual project.
        cycleEntered = new CountDownLatch(1);
        stubEngine(400);
        queuedReruns().add(1L);

        Method runClaimed = SyncTaskRunner.class.getDeclaredMethod(
                "runClaimedRerun", Long.class);
        runClaimed.setAccessible(true);
        Thread rerun = new Thread(() -> {
            try {
                runClaimed.invoke(runner, 1L);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        rerun.start();
        assertThat(cycleEntered.await(3, TimeUnit.SECONDS)).isTrue();

        // The click lands while the rerun's cycle holds the lock: coalesced, not executed.
        assertThat(runner.runOnce(1L).kind()).isEqualTo(SyncTaskRunner.Outcome.Kind.QUEUED);

        rerun.join(5000);
        // The rerun must serve the click itself: two cycles, and nothing left behind.
        awaitCycles(2);
        awaitQueueDrained();
    }

    @Test
    void aRerunWhoseLocalHolderJustFinishedServesItselfInsteadOfStranding() throws Exception {
        // runClaimedRerun 撞上"本 JVM 里还有持锁者"时,条目交给那个持锁者收尾。但持锁者
        // 可能在 tryAcquire 失败之后、我们把条目放回队列之前就结束并排空了队列——
        // 这个窗口里的条目再没人消费,等到项目下一次自然运行才被幽灵触发。
        // 契约:放回队列后像 runOnce 一样复查本地持锁者,没人了就自己接手。
        SyncLockService lockService = mock(SyncLockService.class);
        SyncLockService.LockHandle handle = mock(SyncLockService.LockHandle.class);
        when(lockService.tryAcquire(9L)).thenReturn(null, handle);
        when(lockService.isLockedInThisJvm(9L)).thenReturn(true, false); // 检查后本地持锁者已退出
        Project project = new Project();
        project.setId(9L);
        project.setName("p9");
        project.setEnabled(true);
        when(projectRepository.findById(9L)).thenReturn(Optional.of(project));
        SyncEngine engine = mock(SyncEngine.class);
        when(engine.runCycle(any())).thenAnswer(invocation -> {
            cycles.incrementAndGet();
            return new SyncResult();
        });
        SyncTaskRunner local = new SyncTaskRunner(projectRepository,
                mock(SyncContextFactory.class), engine, lockService, taskStore,
                new PoolActivity(new SyncProperties()));
        try {
            queuedReruns(local).add(9L);
            Method runClaimed = SyncTaskRunner.class.getDeclaredMethod(
                    "runClaimedRerun", Long.class);
            runClaimed.setAccessible(true);
            runClaimed.invoke(local, 9L);

            long deadline = System.currentTimeMillis() + 3000;
            while (cycles.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(cycles.get()).as("队列条目必须被自己接手,而不是留给已经不存在的持锁者")
                    .isEqualTo(1);
            assertThat(queuedReruns(local)).isEmpty();
            verify(lockService, org.mockito.Mockito.times(2)).tryAcquire(9L);
        } finally {
            local.shutdownRerunExecutor();
        }
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

    /**
     * 一次调度触发只需要项目行一次:存在性与 enabled 都出自同一次读。分成两次查
     * (existsById + findById)时,两次读之间的删除会让第二次读到空,孤儿分支反而
     * 走进"已停用"分支——日志说错了原因。
     */
    @Test
    void findProjectReturnsTheSingleRowTheFireReads() {
        assertThat(runner.findProject(1L)).get()
                .extracting(Project::getId).isEqualTo(1L);
        assertThat(runner.findProject(2L)).isEmpty();
    }

    @Test
    void forgetDropsAllInMemoryStateOfADeletedProject() throws Exception {
        // 删除项目后,这三张表里的键会一直留到进程重启:队列条目、失败计数、停机代数。
        // 其中队列条目最糟——同名新项目若拿到同一个 id,会带着旧条目"幽灵"多跑一轮。
        queuedReruns().add(9L);
        java.util.Map<Long, Integer> attempts =
                field(runner, "rerunAttempts");
        attempts.put(9L, 3);
        java.util.Map<Long, java.util.concurrent.atomic.AtomicLong> epochs =
                field(runner, "stopEpochs");
        epochs.put(9L, new java.util.concurrent.atomic.AtomicLong(5L));

        runner.forget(9L);

        assertThat(queuedReruns()).doesNotContain(9L);
        assertThat(attempts).doesNotContainKey(9L);
        assertThat(epochs).doesNotContainKey(9L);
    }

    @Test
    void forgetAlsoClearsTheIdleHeartbeatMarkerOfADeletedProject() throws Exception {
        Field field = SyncJob.class.getDeclaredField("LAST_IDLE_HEARTBEAT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, Long> heartbeats = (java.util.Map<Long, Long>) field.get(null);
        heartbeats.put(9L, System.currentTimeMillis());

        runner.forget(9L);

        assertThat(heartbeats).doesNotContainKey(9L);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(SyncTaskRunner target, String name)
            throws ReflectiveOperationException {
        Field field = SyncTaskRunner.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> queuedReruns() throws ReflectiveOperationException {
        return queuedReruns(runner);
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> queuedReruns(SyncTaskRunner target) throws ReflectiveOperationException {
        Field field = SyncTaskRunner.class.getDeclaredField("queuedReruns");
        field.setAccessible(true);
        return (Set<Long>) field.get(target);
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

}
