package com.qqmu.jync.service.task;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

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
     * Injected by {@link org.springframework.scheduling.quartz.SpringBeanJobFactory}; Quartz
     * instantiates the job itself, so constructor injection is not available.
     */
    @Autowired
    private SyncTaskRunner runner;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long projectId = context.getMergedJobDataMap().getLong(PROJECT_ID);
        if (!runner.projectExists(projectId)) {
            // The project was deleted but a delete-vs-schedule race left the Quartz job
            // behind; delete the orphan instead of firing forever into a warn-only log.
            log.warn("Scheduled sync found no project {}; deleting the orphaned job", projectId);
            try {
                context.getScheduler().deleteJob(context.getJobDetail().getKey());
            } catch (SchedulerException e) {
                log.error("Could not delete the orphaned job for project {}: {}",
                        projectId, e.getMessage());
            }
            return;
        }
        if (!runner.projectEnabled(projectId)) {
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
            return;
        }
        try {
            runner.runOnce(projectId)
                    .result()
                    .ifPresent(result -> log.debug("Scheduled sync of project {} -> {}",
                            projectId, result.summary()));
        } catch (RuntimeException e) {
            // Never let an exception escape into Quartz: an unhandled error can cause the
            // trigger to be unscheduled, which would silently stop syncing this project.
            log.error("Scheduled sync of project {} threw an unexpected exception", projectId, e);
        }
    }
}
