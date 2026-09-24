package com.qqmu.jync.service.task;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Runs a queued rerun off the requesting thread, so no click ever waits for two cycles. */
    private final ExecutorService rerunExecutor = Executors.newSingleThreadExecutor(runnable -> {
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

        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                // Another runner holds the lock, so the work is already happening — but this
                // click may know about changes the running cycle's window no longer covers.
                // Remember it as at most one rerun instead of dropping it.
                queuedReruns.add(projectId);
                return Outcome.queued();
            }
            SyncResult result = execute(project, lock);
            startQueuedRerun(projectId);
            return Outcome.executed(result);
        }
    }

    /**
     * Hands a coalesced rerun to the background executor now that a cycle has finished.
     *
     * <p>The rerun re-acquires the lock over there; if a scheduled fire grabbed it in between,
     * the request re-registers and is served when that cycle finishes in turn.
     */
    private void startQueuedRerun(Long projectId) {
        if (queuedReruns.remove(projectId)) {
            log.info("Sync of project {} finished; running the queued extra cycle", projectId);
            rerunExecutor.submit(() -> runOnce(projectId));
        }
    }

    @PreDestroy
    void shutdownRerunExecutor() {
        rerunExecutor.shutdown();
    }

    private SyncResult execute(Project project, SyncLockService.LockHandle lock) {
        long start = System.currentTimeMillis();
        taskStore.markRunning(project.getId());

        SyncResult result;
        try {
            SyncContext ctx = contextFactory.build(project, lock.owner());
            // The lease is renewed between tables; a failed renewal aborts the cycle before
            // the next table's writes, because the lock may now belong to another instance.
            result = syncEngine.runCycle(ctx, lock::renew);
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

        taskStore.recordOutcome(project.getId(), result);
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
