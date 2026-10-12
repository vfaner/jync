package com.qqmu.jync.service.task;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;

import lombok.extern.slf4j.Slf4j;

/**
 * The multi-instance control plane: periodically re-matches this node's local Quartz
 * schedules against the durable {@code enabled} flags in the shared database.
 *
 * <p>With a single instance this is a no-op — start/stop already keep the local schedule in
 * step, and {@link SyncBootstrap} rebuilds it at startup. With several instances sharing one
 * database it closes the gap RAMJobStore leaves: Quartz state is per-node, so a project
 * started on node A does not exist for node B's scheduler until B hears about it. Here,
 * every node converges within one reconcile interval:
 *
 * <ul>
 *   <li>enabled in the database but not scheduled here → schedule it (started elsewhere);</li>
 *   <li>scheduled here but disabled in the database → unschedule it (stopped elsewhere).</li>
 * </ul>
 *
 * <p>{@link SyncJob} additionally re-checks the flag at fire time, so the window between a
 * remote stop and the next reconcile tick cannot run a cycle either. Interval/cron changes
 * made on another node are picked up the same way once that project is next saved here;
 * the reconcile only guarantees <em>presence</em>, not the exact trigger spec.
 */
@Component
@Slf4j
public class ScheduleReconciler {

    private final ProjectRepository projectRepository;
    private final SyncScheduler scheduler;
    private final CdcConsumerManager cdcManager;

    public ScheduleReconciler(ProjectRepository projectRepository, SyncScheduler scheduler,
                              CdcConsumerManager cdcManager) {
        this.projectRepository = projectRepository;
        this.scheduler = scheduler;
        this.cdcManager = cdcManager;
    }

    /** Fixed delay, and the first tick lands one interval after startup — SyncBootstrap owns startup. */
    @Scheduled(fixedDelayString = "${sync.reconcile-interval-ms:30000}")
    public void reconcile() {
        List<Project> projects;
        try {
            projects = projectRepository.findAll();
        } catch (RuntimeException e) {
            // A hiccup reading the database must not kill the periodic task; next tick retries.
            log.warn("Schedule reconciliation could not read projects, retrying next tick: {}",
                    e.toString());
            return;
        }
        for (Project project : projects) {
            try {
                reconcileOne(project);
            } catch (RuntimeException e) {
                // One broken project (bad cron, vanished row) must not stop the others.
                log.error("Schedule reconciliation failed for project '{}': {}",
                        project.getName(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Project project) {
        CaptureStrategy strategy = cdcManager.strategyOf(project);   // syncConfig JSON 解析归口 manager
        if (strategy != null && strategy.isLogCdc()) {
            // CDC 项目归 CdcConsumerManager 管,永不建 Quartz job;残留的轮询 job(例如
            // 策略刚从 POLLING 切过来)在这里幂等清掉,避免双引擎双写。
            scheduler.removePollingJob(project.getId());   // 幂等:没有 job 也无害
            if (Boolean.TRUE.equals(project.getEnabled())) {
                cdcManager.ensureStarted(project);
            } else {
                cdcManager.ensureStopped(project.getId());
            }
            return;
        }
        cdcManager.ensureStopped(project.getId());          // 非 CDC:确保没有残留消费者
        boolean enabled = Boolean.TRUE.equals(project.getEnabled());
        boolean scheduled = scheduler.isScheduled(project.getId());
        if (enabled && !scheduled) {
            log.info("Reconciliation: project '{}' is enabled but not scheduled on this node;"
                    + " scheduling it", project.getName());
            scheduler.schedule(project);
        } else if (!enabled && scheduled) {
            log.info("Reconciliation: project '{}' is stopped but still scheduled on this node;"
                    + " removing the schedule", project.getName());
            scheduler.unschedule(project.getId());
        }
    }
}
