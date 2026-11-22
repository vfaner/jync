package com.qqmu.jync.service.task;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Restores sync state after a restart.
 *
 * <p>This is what makes "resume after a crash" work end to end. On startup every project
 * marked enabled is rescheduled: Quartz is configured with an in-memory job store, so no
 * schedule survives a restart; the durable truth is the {@code enabled} flag on the project,
 * and the schedule is rebuilt from it. Locks a crashed instance left behind are not cleared
 * here — each owner gets a random id, so no startup process can match them; they clear on
 * their own by lease expiry (lockTtlMs).
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
    private final CdcConsumerManager cdcManager;
    private final SyncTaskStore taskStore;
    private final SyncLockService lockService;

    public SyncBootstrap(ProjectRepository projectRepository, SyncScheduler scheduler,
                         CdcConsumerManager cdcManager, SyncTaskStore taskStore,
                         SyncLockService lockService) {
        this.projectRepository = projectRepository;
        this.scheduler = scheduler;
        this.cdcManager = cdcManager;
        this.taskStore = taskStore;
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
        healPhantomRunning();
        List<Project> enabled = projectRepository.findByEnabledTrue();
        if (enabled.isEmpty()) {
            log.info("No enabled projects to resume");
            return;
        }

        int resumed = 0;
        for (Project project : enabled) {
            try {
                scheduler.schedule(project);   // BINLOG_CDC 项目在 schedule() 守卫处直接返回
                resumed++;
            } catch (RuntimeException e) {
                // One misconfigured project must not stop the others from resuming.
                log.error("Could not resume project '{}': {}", project.getName(), e.getMessage());
            }
        }
        // CDC 消费者接管:Reconciler 首轮(30s)与 supervise(10s)也会兜底,这里只是
        // 让重启后的续流快一拍;ensureStarted 幂等,三方重叠无副作用。
        int consumers = 0;
        for (Project project : enabled) {
            if (!cdcManager.strategyOf(project).isLogCdc()) {
                continue;
            }
            try {
                cdcManager.ensureStarted(project);
                consumers++;
            } catch (RuntimeException e) {
                log.error("Could not start the CDC consumer for project '{}': {}",
                        project.getName(), e.getMessage());
            }
        }
        log.info("Resumed {} of {} enabled project(s); each continues from its stored cursor"
                        + " ({} CDC consumer(s) handed to CdcConsumerManager)",
                resumed, enabled.size(), consumers);
    }

    /**
     * Re-runs the phantom-status heal. The startup pass cannot clear a lease that is still
     * inside its TTL under a crashed process's random owner id, so a lease left by kill -9
     * (or an upgrade's SIGKILL) would keep a stopped project's status RUNNING forever.
     * A minute after startup, and every minute after, an expired lease is cleared; a live
     * cycle — this node's or another's — renews its lease and is skipped.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            initialDelayString = "${sync.lock-ttl-ms:300000}", fixedDelay = 60_000L)
    public void healStaleStatuses() {
        healPhantomRunning();
    }

    /**
     * A kill -9 mid-cycle leaves {@code sync_task.status = RUNNING} behind. Enabled projects
     * self-heal on their next fire, but a stopped project synced by hand never fires again,
     * so its page and the dashboard would claim "syncing" forever. The lease is the truth
     * about whether a cycle is actually in flight: no live holder means nobody is running,
     * whatever the status column says. ERROR hats are preserved — a parked project must not
     * be silently unparked by a restart.
     */
    private void healPhantomRunning() {
        List<SyncTask> running;
        try {
            running = taskStore.findByStatus(TaskStatus.RUNNING);
        } catch (RuntimeException e) {
            log.warn("Could not read running tasks at startup: {}", e.toString());
            return;
        }
        int healed = 0;
        for (SyncTask task : running) {
            try {
                if (lockService.isLeaseHeldByLiveOwner(task.getProjectId())) {
                    continue;   // a cycle is genuinely in flight (here or on another node)
                }
                taskStore.markStoppedUnlessError(task.getProjectId());
                healed++;
            } catch (RuntimeException e) {
                log.warn("Could not heal stale RUNNING status for project {}: {}",
                        task.getProjectId(), e.toString());
            }
        }
        if (healed > 0) {
            log.info("Reset {} stale RUNNING task status(es) left by a previous process", healed);
        }
    }
}
