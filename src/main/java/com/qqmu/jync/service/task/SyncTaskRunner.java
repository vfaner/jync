package com.qqmu.jync.service.task;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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
            return;
        }
        Project project = maybeProject.get();
        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                // A holder is active; return the entry for its drain, re-checking the gap.
                queuedReruns.add(projectId);
                selfServeQueuedRerun(projectId);
                return;
            }
            execute(project, lock);
        }
    }

    /** Whether a project row still exists; an orphaned Quartz job uses it to self-delete. */
    public boolean projectExists(Long projectId) {
        return projectRepository.existsById(projectId);
    }

    @PreDestroy
    void shutdownRerunExecutor() {
        rerunExecutor.shutdown();
    }

    private SyncResult execute(Project project, SyncLockService.LockHandle lock) {
        long start = System.currentTimeMillis();
        SyncResult result;
        try {
            // markRunning lives INSIDE the try: throwing before it used to escape execute()
            // entirely, so runOnce never reached the queued-rerun drain.
            taskStore.markRunning(project.getId());
            // The renewer rides on the context: the structure phase and each committed data
            // batch renew through it, so a lost lease aborts the cycle before further writes.
            SyncContext ctx = contextFactory.build(project, lock.owner(), lock::renew);
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
            taskStore.recordOutcome(project.getId(), result);
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
