package com.qqmu.jync.service.task;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs one sync cycle for a project under that project's lock, and records the outcome.
 *
 * <p>This is the only entry point for executing a sync, whether triggered by the scheduler or
 * manually from the UI. Routing both through here is what makes the lock meaningful: a manual
 * run and a scheduled fire contend for the same lock instead of racing each other.
 *
 * <p>A manual request that arrives while a cycle is in flight is not dropped. The running
 * cycle's window was cut when it started, so changes committed after that moment would
 * otherwise wait for the next scheduled poll; instead the click is coalesced — any number of
 * clicks become one queued rerun — and served on a background thread once the lock frees up.
 */
@Service
@Slf4j
public class SyncTaskRunner {

    private final ProjectRepository projectRepository;
    private final SyncContextFactory contextFactory;
    private final SyncEngine syncEngine;
    private final SyncLockService lockService;
    private final SyncTaskStore taskStore;

    /** Projects whose in-flight cycle should be followed by exactly one more cycle. */
    private final Set<Long> queuedReruns = ConcurrentHashMap.newKeySet();

    /**
     * How many consecutive background attempts each queued entry has made against a lock
     * held outside this JVM.
     *
     * <p>Without a cap a stuck lock (dead instance within its TTL, long maintenance window)
     * turns one click into an endless chain: failed acquire → requeue → 1 second later,
     * failed acquire → ... Reset whenever the lock is taken or a holder inside this JVM is
     * found instead.
     */
    private final Map<Long, Integer> rerunAttempts = new ConcurrentHashMap<>();

    /** Max background attempts against an out-of-JVM holder before the click is dropped;
     *  the next scheduled poll or manual click starts a fresh chain. */
    private static final int MAX_RERUN_ATTEMPTS = 5;

    /**
     * Wait before a self-served rerun retries a still-busy lock, so a lock held by another
     * instance is polled instead of busy-spun.
     */
    private static final long RERUN_RETRY_MS = 1000L;

    /**
     * Runs queued reruns off the requesting thread, so no click ever waits for two cycles.
     *
     * <p>A cached pool rather than a single thread: one project's delayed retry (or a slow
     * cycle) must not block another project's queued rerun behind it. Submissions are rare
     * and short, and the threads are daemons.
     */
    private final ExecutorService rerunExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jync-rerun");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Per-project stop generation. {@link #requestStop} bumps it; a cycle captures the value
     * when it starts and aborts as soon as it changes. A counter rather than a flag: a run
     * that starts after the stop (the user restarts, or clicks 立即同步) must NOT inherit the
     * previous stop, and a flag cleared at start would race a cycle that is still unwinding.
     */
    private final Map<Long, AtomicLong> stopEpochs = new ConcurrentHashMap<>();

    public SyncTaskRunner(ProjectRepository projectRepository,
                          SyncContextFactory contextFactory,
                          SyncEngine syncEngine,
                          SyncLockService lockService,
                          SyncTaskStore taskStore) {
        this.projectRepository = projectRepository;
        this.contextFactory = contextFactory;
        this.syncEngine = syncEngine;
        this.lockService = lockService;
        this.taskStore = taskStore;
    }

    /**
     * Executes one cycle if the lock can be taken.
     *
     * @return an {@link Outcome}: executed with the result, queued when a cycle was already
     *         in flight (this request will be served right after it), or no project
     */
    public Outcome runOnce(Long projectId) {
        Optional<Project> maybeProject = projectRepository.findById(projectId);
        if (maybeProject.isEmpty()) {
            log.warn("Sync requested for project {}, which no longer exists", projectId);
            return Outcome.noProject();
        }
        Project project = maybeProject.get();
        taskStore.ensureTask(project);

        SyncResult result;
        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                // Another runner holds the lock, so the work is already happening — but this
                // click may know about changes the running cycle's window no longer covers.
                // Remember it as at most one rerun instead of dropping it.
                queuedReruns.add(projectId);
                // The holder may have released in the gap before this add; serve the entry
                // ourselves if no local holder remains to drain it.
                selfServeQueuedRerun(projectId);
                return Outcome.queued();
            }
            result = execute(project, lock);
        }
        // 队列检查必须排在锁释放之后。持锁时检查的话，"检查为空 → 释放锁"这个窗口里到达的
        // 请求 tryAcquire 失败、入队，却再没有持锁者来消费它——条目会一直残留到该项目
        // 下一次运行时被幽灵触发（对手动项目可能是很久以后）。
        startQueuedRerun(projectId);
        return Outcome.executed(result);
    }

    /**
     * Hands a coalesced rerun to the background executor now that a cycle has finished.
     *
     * <p>The rerun re-acquires the lock over there; if a scheduled fire grabbed it in between,
     * the task re-registers and is served when that cycle finishes in turn.
     */
    private void startQueuedRerun(Long projectId) {
        if (queuedReruns.remove(projectId)) {
            log.info("Sync of project {} finished; running the queued extra cycle", projectId);
            scheduleRerun(projectId, 0L);
        }
    }

    /**
     * Serves a queued entry from the background pool when no local holder is left to drain
     * it: the holder released in the gap between the failed tryAcquire and the queue add,
     * or another instance holds the database lock.
     *
     * <p>Claims the entry before scheduling so two observers racing here collapse into one
     * task; with a live holder the entry stays put for its post-cycle drain.
     */
    private void selfServeQueuedRerun(Long projectId) {
        if (lockService.isLockedInThisJvm(projectId)) {
            return;
        }
        if (queuedReruns.remove(projectId)) {
            scheduleRerun(projectId, RERUN_RETRY_MS);
        }
    }

    /**
     * Submits a claimed rerun to the background pool, waiting {@code delayMs} first. The
     * delay keeps a database lock held by another instance from turning into a busy retry.
     */
    private void scheduleRerun(Long projectId, long delayMs) {
        if (rerunExecutor.isShutdown()) {
            return;
        }
        try {
            rerunExecutor.submit(() -> {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                runClaimedRerun(projectId);
            });
        } catch (RejectedExecutionException e) {
            log.debug("Queued rerun for project {} was not accepted by the executor", projectId);
        }
    }

    /**
     * Runs one cycle for an already-claimed entry, or hands the entry back to a holder that
     * appeared in the meantime.
     */
    private void runClaimedRerun(Long projectId) {
        Optional<Project> maybeProject = projectRepository.findById(projectId);
        if (maybeProject.isEmpty()) {
            log.debug("Dropping queued rerun: project {} no longer exists", projectId);
            rerunAttempts.remove(projectId);
            return;
        }
        Project project = maybeProject.get();
        if (!Boolean.TRUE.equals(project.getEnabled())) {
            // The project was stopped (or deleted-then-recreated into a disabled state) after
            // the click coalesced. Do not sneak a cycle past the user's stop.
            log.info("Dropping queued rerun for project '{}': the project is stopped",
                    project.getName());
            queuedReruns.remove(projectId);
            rerunAttempts.remove(projectId);
            return;
        }
        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                // A holder inside this JVM drains us when its cycle finishes; do not spend
                // retry attempts on that ordinary wait.
                if (lockService.isLockedInThisJvm(projectId)) {
                    queuedReruns.add(projectId);
                    return;
                }
                int attempt = rerunAttempts.merge(projectId, 1, Integer::sum);
                if (attempt >= MAX_RERUN_ATTEMPTS) {
                    // Another instance holding the lock is the expected steady state, and
                    // this repeats on every scheduled fire while it lasts; keep it at DEBUG.
                    log.debug("Queued rerun for project '{}' gave up after {} attempts to take"
                            + " a lock held outside this process; it will run at the next"
                            + " scheduled or manual trigger", project.getName(), attempt);
                    queuedReruns.remove(projectId);
                    rerunAttempts.remove(projectId);
                    return;
                }
                // A holder is active; return the entry for its drain, re-checking the gap.
                queuedReruns.add(projectId);
                selfServeQueuedRerun(projectId);
                return;
            }
            execute(project, lock);
            rerunAttempts.remove(projectId);
        }
        // A rerun is a holder too: a click that arrived while ITS cycle ran was coalesced
        // into queuedReruns and is waiting for this drain. Same ordering rule as runOnce —
        // after the lock release, or the check-then-release window strands the entry.
        startQueuedRerun(projectId);
    }

    /**
     * Asks the project's in-flight polling cycle to stop.
     *
     * <p>Stopping only the schedule leaves the running round in charge: a full load renews
     * its lease for minutes, and while it does, every config save is refused with "stop the
     * sync first" — advice the user just followed. The cycle picks this up at its next
     * committed batch ({@link SyncContext#requireLease()}), so nothing is cut mid-write.
     *
     * <p>Deliberately not awaited here: the caller has already committed the stop, and a
     * batch can take a while. The cycle releases the lease as it unwinds, which is what the
     * config guards observe.
     */
    public void requestStop(Long projectId) {
        stopEpochs.computeIfAbsent(projectId, id -> new AtomicLong()).incrementAndGet();
    }

    private long stopEpoch(Long projectId) {
        AtomicLong epoch = stopEpochs.get(projectId);
        return epoch == null ? 0L : epoch.get();
    }

    /** Whether a project row still exists; an orphaned Quartz job uses it to self-delete. */
    public boolean projectExists(Long projectId) {
        return projectRepository.existsById(projectId);
    }

    /**
     * Whether the project exists <em>and</em> its durable enabled flag is set.
     *
     * <p>Asked at every scheduled fire rather than trusted from the local schedule: with
     * several instances sharing one database, a stop processed on another node leaves this
     * node's Quartz trigger alive, and the database flag is the only truth all nodes see.
     * Manual sync-now deliberately does not consult this — running one deliberate cycle on a
     * stopped project stays a feature, not a leak.
     */
    public boolean projectEnabled(Long projectId) {
        return projectRepository.findById(projectId)
                .map(p -> Boolean.TRUE.equals(p.getEnabled()))
                .orElse(false);
    }

    @PreDestroy
    void shutdownRerunExecutor() {
        rerunExecutor.shutdown();
    }

    private SyncResult execute(Project project, SyncLockService.LockHandle lock) {
        long start = System.currentTimeMillis();
        // Snapshot BEFORE the cycle does any work: a stop that lands after this point aborts
        // this run, while one that landed before it belongs to whatever ran previously.
        long stopEpoch = stopEpoch(project.getId());
        SyncContext ctx = null;
        SyncResult result;
        try {
            // markRunning lives INSIDE the try: throwing before it used to escape execute()
            // entirely, so runOnce never reached the queued-rerun drain.
            taskStore.markRunning(project.getId());
            // The renewer rides on the context: the structure phase and each committed data
            // batch renew through it, so a lost lease aborts the cycle before further writes.
            // The stop signal rides along too, checked at those same safe points.
            ctx = contextFactory.build(project, lock.owner(), lock::renew,
                    () -> stopEpoch(project.getId()) != stopEpoch);
            result = syncEngine.runCycle(ctx);
        } catch (IllegalStateException e) {
            // Configuration problems — missing endpoint, unreachable database — land here.
            log.error("Cannot run sync for project '{}': {}", project.getName(), e.getMessage());
            result = new SyncResult();
            result.addError(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected failure syncing project '{}'", project.getName(), e);
            result = new SyncResult();
            result.addError(String.valueOf(e.getMessage()));
        }
        result.setDurationMs(System.currentTimeMillis() - start);

        // Outcome recording must not escape execute() either: a failure here previously
        // skipped startQueuedRerun, leaving the coalesced request stuck in the queue.
        try {
            // A run the user stopped is not a failed attempt: recording it through the
            // failure ladder would show "FAILED" for a deliberate action and push a healthy
            // project towards the max-retries ERROR hat after a few ordinary stops.
            boolean stopped = ctx != null && ctx.isStopRequested() && !result.isSuccess();
            if (stopped) {
                // Also visible to callers: syncNow's HTTP response must say 「已停止」 rather
                // than 「有失败项」 for a round the user ended themselves.
                result.setStopped(true);
                taskStore.recordStoppedOutcome(project.getId(), result);
            } else {
                taskStore.recordOutcome(project.getId(), result);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record sync outcome for project '{}'",
                    project.getName(), e);
        }
        if (result.hasChanges() || !result.isSuccess()) {
            log.info("Sync of '{}' finished: {}", project.getName(), result.summary());
        }
        return result;
    }

    /** What became of a sync request. */
    public static final class Outcome {

        public enum Kind { EXECUTED, QUEUED, NO_PROJECT }

        private final Kind kind;
        private final SyncResult result;

        private Outcome(Kind kind, SyncResult result) {
            this.kind = kind;
            this.result = result;
        }

        static Outcome executed(SyncResult result) {
            return new Outcome(Kind.EXECUTED, result);
        }

        static Outcome queued() {
            return new Outcome(Kind.QUEUED, null);
        }

        static Outcome noProject() {
            return new Outcome(Kind.NO_PROJECT, null);
        }

        public Kind kind() {
            return kind;
        }

        public Optional<SyncResult> result() {
            return Optional.ofNullable(result);
        }
    }
}
