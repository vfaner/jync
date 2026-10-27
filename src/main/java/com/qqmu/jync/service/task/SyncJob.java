package com.qqmu.jync.service.task;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;

import lombok.extern.slf4j.Slf4j;

/**
 * The Quartz job that drives one project's polling.
 *
 * <p>{@link DisallowConcurrentExecution} is the first line of the concurrency guard: with a
 * poll interval of two seconds and a cycle that occasionally takes longer, Quartz would
 * otherwise start overlapping fires of the same job. The annotation makes the scheduler skip a
 * fire while the previous one is still running.
 *
 * <p>It is necessary but not sufficient — it only constrains one scheduler instance, which is
 * why {@link SyncLockService} adds a JVM lock and a database lease on top.
 */
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
@Slf4j
public class SyncJob implements Job {

    /** Key under which the project id is stored in the job data map. */
    public static final String PROJECT_ID = "projectId";

    /**
     * Key under which the project name is stored in the job data map. Carried in the job
     * itself because a log line that names the project must still be readable when the row
     * is gone (a deleted project leaves an orphaned job for one more fire).
     */
    public static final String PROJECT_NAME = "projectName";

    /**
     * How often an idle project still writes one completion line at INFO. The per-fire
     * details stay at DEBUG: with a 2-second poll interval, two INFO lines per fire turn one
     * idle project into ~86,000 lines a day, which buries the cycles that actually changed
     * something. A periodic heartbeat keeps the "did my schedule fire?" answer without it.
     */
    private static final long IDLE_HEARTBEAT_MS = 10 * 60 * 1000L;

    private static final ConcurrentMap<Long, Long> LAST_IDLE_HEARTBEAT =
            new ConcurrentHashMap<>();

    static void resetIdleHeartbeatForTests() {
        LAST_IDLE_HEARTBEAT.clear();
    }

    /** Drops the heartbeat marker of a deleted project; see {@link SyncTaskRunner#forget}. */
    static void forgetProject(Long projectId) {
        LAST_IDLE_HEARTBEAT.remove(projectId);
    }

    /**
     * Injected by {@link org.springframework.scheduling.quartz.SpringBeanJobFactory}; Quartz
     * instantiates the job itself, so constructor injection is not available.
     */
    @Autowired
    private SyncTaskRunner runner;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long projectId = context.getMergedJobDataMap().getLong(PROJECT_ID);
        String project = label(context.getMergedJobDataMap().getString(PROJECT_NAME), projectId);
        long startedAt = System.nanoTime();
        // Per-fire detail is DEBUG; finish() promotes a cycle that changed something, failed,
        // or is due for the idle heartbeat to INFO.
        log.debug("Project '{}': scheduled sync starting", project);

        // One repository read answers both questions, so a delete cannot land between them
        // and make the enabled check masquerade as the orphan case (or the reverse).
        Project projectRow = runner.findProject(projectId).orElse(null);
        if (projectRow == null) {
            // The project was deleted but a delete-vs-schedule race left the Quartz job
            // behind; delete the orphan instead of firing forever into a warn-only log.
            log.warn("Scheduled sync found no project {}; deleting the orphaned job", projectId);
            try {
                context.getScheduler().deleteJob(context.getJobDetail().getKey());
            } catch (SchedulerException e) {
                log.error("Could not delete the orphaned job for project {}: {}",
                        projectId, e.getMessage());
            }
            runner.forget(projectId);       // 项目没了:内存里的队列/计数/心跳一并丢掉
            finish(projectId, project, startedAt,
                    "project no longer exists; orphaned job removed", true);
            return;
        }
        if (!Boolean.TRUE.equals(projectRow.getEnabled())) {
            // Stopped — possibly on another instance sharing the database, which cannot
            // remove this node's trigger. The enabled flag is the durable truth, so drop
            // the stale local schedule instead of running cycles for a stopped project.
            log.info("Scheduled sync found project {} disabled; removing the stale local job",
                    projectId);
            try {
                context.getScheduler().deleteJob(context.getJobDetail().getKey());
            } catch (SchedulerException e) {
                log.error("Could not remove the stale schedule for disabled project {}: {}",
                        projectId, e.getMessage());
            }
            finish(projectId, project, startedAt, "project is stopped; schedule removed", true);
            return;
        }
        try {
            SyncTaskRunner.Outcome outcome = runner.runOnce(projectId);
            String detail = outcome.result()
                    .map(SyncResult::summary)
                    .orElse("another cycle is still running; this fire was queued");
            boolean noteworthy = outcome.result()
                    .map(result -> result.hasChanges() || !result.isSuccess())
                    .orElse(false);
            finish(projectId, project, startedAt, detail, noteworthy);
        } catch (RuntimeException e) {
            // Never let an exception escape into Quartz: an unhandled error can cause the
            // trigger to be unscheduled, which would silently stop syncing this project.
            log.error("Scheduled sync of project {} threw an unexpected exception", projectId, e);
            finish(projectId, project, startedAt, "failed: " + e.getMessage(), true);
        }
    }

    private static void finish(Long projectId, String project, long startedAt, String detail,
                               boolean noteworthy) {
        String elapsed = String.format(java.util.Locale.ROOT, "%.1f",
                (System.nanoTime() - startedAt) / 1_000_000_000.0);
        if (noteworthy || idleHeartbeatDue(projectId)) {
            log.info("Project '{}': scheduled sync finished in {}s — {}", project, elapsed, detail);
        } else {
            log.debug("Project '{}': scheduled sync finished in {}s — {}", project, elapsed, detail);
        }
    }

    /** True for the first idle fire and then at most once per {@link #IDLE_HEARTBEAT_MS}. */
    private static boolean idleHeartbeatDue(Long projectId) {
        long now = System.currentTimeMillis();
        boolean[] due = {false};
        LAST_IDLE_HEARTBEAT.compute(projectId, (id, last) -> {
            if (last == null || now - last >= IDLE_HEARTBEAT_MS) {
                due[0] = true;
                return now;
            }
            return last;
        });
        return due[0];
    }

    private static String label(String name, Long projectId) {
        return name == null || name.isBlank() ? String.valueOf(projectId) : name;
    }
}
