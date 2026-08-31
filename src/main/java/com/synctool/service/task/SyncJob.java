package com.synctool.service.task;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
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
        try {
            runner.runOnce(projectId)
                    .ifPresent(result -> log.debug("Scheduled sync of project {} -> {}",
                            projectId, result.summary()));
        } catch (RuntimeException e) {
            // Never let an exception escape into Quartz: an unhandled error can cause the
            // trigger to be unscheduled, which would silently stop syncing this project.
            log.error("Scheduled sync of project {} threw an unexpected exception", projectId, e);
        }
    }
}
