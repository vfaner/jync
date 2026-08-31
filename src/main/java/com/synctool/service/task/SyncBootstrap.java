package com.synctool.service.task;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.synctool.model.Project;
import com.synctool.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Restores sync state after a restart.
 *
 * <p>This is what makes "resume after a crash" work end to end. On startup:
 *
 * <ol>
 *   <li>Locks left behind by this instance's previous run are released. They would otherwise
 *       block their projects until the lease expired, because the process that held them is
 *       gone and cannot release them itself.
 *   <li>Every project marked enabled is rescheduled. Quartz is configured with an in-memory
 *       job store, so no schedule survives a restart; the durable truth is the {@code enabled}
 *       flag on the project, and the schedule is rebuilt from it.
 * </ol>
 *
 * <p>No sync progress is reset. Each table's cursor and each object's snapshot are already
 * durable, so a resumed project picks up exactly where it left off — and because the cursor is
 * only advanced after a committed target write, the window that was in flight when the process
 * died is simply re-read and re-applied idempotently.
 */
@Component
@Slf4j
public class SyncBootstrap {

    private final ProjectRepository projectRepository;
    private final SyncScheduler scheduler;
    private final SyncLockService lockService;

    public SyncBootstrap(ProjectRepository projectRepository, SyncScheduler scheduler,
                         SyncLockService lockService) {
        this.projectRepository = projectRepository;
        this.scheduler = scheduler;
        this.lockService = lockService;
    }

    /**
     * Runs once the context is fully up.
     *
     * <p>{@link ApplicationReadyEvent} rather than {@code @PostConstruct} so the Quartz
     * scheduler and both data sources are guaranteed to be initialized before any job is
     * registered.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onReady() {
        lockService.releaseStaleLocksOfThisOwner();

        List<Project> enabled = projectRepository.findByEnabledTrue();
        if (enabled.isEmpty()) {
            log.info("No enabled projects to resume");
            return;
        }

        int resumed = 0;
        for (Project project : enabled) {
            try {
                scheduler.schedule(project);
                resumed++;
            } catch (RuntimeException e) {
                // One misconfigured project must not stop the others from resuming.
                log.error("Could not resume project '{}': {}", project.getName(), e.getMessage());
            }
        }
        log.info("Resumed {} of {} enabled project(s); each continues from its stored cursor",
                resumed, enabled.size());
    }
}
