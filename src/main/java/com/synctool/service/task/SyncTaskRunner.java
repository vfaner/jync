package com.synctool.service.task;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.synctool.dto.SyncResult;
import com.synctool.model.Project;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.sync.SyncContext;
import com.synctool.service.sync.SyncEngine;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs one sync cycle for a project under that project's lock, and records the outcome.
 *
 * <p>This is the only entry point for executing a sync, whether triggered by the scheduler or
 * manually from the UI. Routing both through here is what makes the lock meaningful: a manual
 * run and a scheduled fire contend for the same lock instead of racing each other.
 */
@Service
@Slf4j
public class SyncTaskRunner {

    private final ProjectRepository projectRepository;
    private final SyncContextFactory contextFactory;
    private final SyncEngine syncEngine;
    private final SyncLockService lockService;
    private final SyncTaskStore taskStore;

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
     * @return the result, or empty when the project was busy or no longer exists
     */
    public Optional<SyncResult> runOnce(Long projectId) {
        Optional<Project> maybeProject = projectRepository.findById(projectId);
        if (maybeProject.isEmpty()) {
            log.warn("Sync requested for project {}, which no longer exists", projectId);
            return Optional.empty();
        }
        Project project = maybeProject.get();
        taskStore.ensureTask(project);

        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                // Another runner holds it. Skipping is correct: the work is already happening.
                return Optional.empty();
            }
            return Optional.of(execute(project, lock));
        }
    }

    private SyncResult execute(Project project, SyncLockService.LockHandle lock) {
        long start = System.currentTimeMillis();
        taskStore.markRunning(project.getId());

        SyncResult result;
        try {
            SyncContext ctx = contextFactory.build(project, lock.owner());
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

        taskStore.recordOutcome(project.getId(), result);
        if (result.hasChanges() || !result.isSuccess()) {
            log.info("Sync of '{}' finished: {}", project.getName(), result.summary());
        }
        return result;
    }
}
