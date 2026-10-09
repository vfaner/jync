package com.qqmu.jync.config;

import java.util.List;

import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

import lombok.extern.slf4j.Slf4j;

/**
 * Gives in-flight sync cycles a bounded grace period during application shutdown.
 *
 * <p>Runs on {@link ContextClosedEvent}, which is published before the scheduler factory bean
 * shuts: triggers are put in standby (no new fires), then executing jobs are polled until they
 * finish or {@code sync.shutdown-wait-ms} elapses. After the bound the scheduler still shuts
 * down non-blockingly, and the database rolls back any open batch, so a wedged cycle can never
 * hang process exit.
 */
@Slf4j
public class BoundedQuartzShutdown {

    private static final long POLL_MS = 200L;

    private final Scheduler scheduler;
    private final SyncProperties properties;

    public BoundedQuartzShutdown(Scheduler scheduler, SyncProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onContextClosed(ContextClosedEvent event) {
        long budget = properties.getShutdownWaitMs();
        if (budget <= 0) {
            return;
        }
        try {
            scheduler.standby();
            long deadline = System.currentTimeMillis() + budget;
            List<JobExecutionContext> active = scheduler.getCurrentlyExecutingJobs();
            while (!active.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS);
                active = scheduler.getCurrentlyExecutingJobs();
            }
            if (!active.isEmpty()) {
                log.warn("Shutdown is abandoning {} sync cycle(s) still running after {}ms;"
                        + " any open batch will be rolled back by the database",
                        active.size(), budget);
            }
        } catch (SchedulerException e) {
            log.debug("Bounded Quartz shutdown wait could not inspect the scheduler: {}",
                    e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
