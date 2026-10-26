package com.qqmu.jync.service.task;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;

import lombok.extern.slf4j.Slf4j;

/**
 * Registers and removes the Quartz job that polls a project.
 *
 * <p>Scheduling is driven by the project's {@code enabled} flag, so start and stop are simply
 * "make the schedule match the flag". That keeps the scheduler convergent: replaying
 * {@link #reschedule} for a project always produces the correct state regardless of what was
 * scheduled before.
 */
@Service
@Slf4j
public class SyncScheduler {

    private final Scheduler scheduler;
    private final SyncProperties properties;
    private final SyncTaskStore taskStore;
    private final SyncContextFactory contextFactory;

    public SyncScheduler(Scheduler scheduler, SyncProperties properties,
                         SyncTaskStore taskStore, SyncContextFactory contextFactory) {
        this.scheduler = scheduler;
        this.properties = properties;
        this.taskStore = taskStore;
        this.contextFactory = contextFactory;
    }

    private JobKey jobKey(Long projectId) {
        return JobKey.jobKey("sync-job-" + projectId, "jync");
    }

    private TriggerKey triggerKey(Long projectId) {
        return TriggerKey.triggerKey("sync-trigger-" + projectId, "jync");
    }

    /**
     * Makes the schedule match the project's enabled flag.
     *
     * <p>Safe to call repeatedly: an existing job is replaced rather than duplicated.
     */
    public synchronized void reschedule(Project project) {
        if (Boolean.TRUE.equals(project.getEnabled())) {
            schedule(project);
        } else {
            unschedule(project.getId());
        }
    }

    /**
     * Registers the polling job, replacing any previous registration.
     *
     * <p>Synchronized: check-exists → delete → schedule is a compound step, and callers
     * overlap (startup re-registration vs. a UI save, two saves in quick flight). Without
     * the monitor a race ends in ObjectAlreadyExistsException or a silently missing job.
     */
    public synchronized void schedule(Project project) {
        SyncConfig config = contextFactory.parseConfig(project);
        if (config.getCaptureStrategyOrDefault().isLogCdc()) {
            // CDC 项目永不进 Quartz:长驻消费者由 CdcConsumerManager 独占(B6)。
            // 轮询与流式双引擎并行 = 目标库双写,守卫放在最前,任何调用方都拦得住。
            log.info("Project '{}' captures via {}; not scheduling a Quartz polling job"
                    + " (CdcConsumerManager owns it)", project.getName(),
                    config.getCaptureStrategyOrDefault());
            return;
        }
        Long projectId = project.getId();
        SyncTask task = taskStore.ensureTask(project);

        // Capture the previous registration so a failed replacement can be rolled back:
        // delete-first used to leave an enabled project with no job at all when the new
        // trigger/registration failed.
        JobDetail oldJob = null;
        Trigger oldTrigger = null;
        try {
            oldJob = scheduler.getJobDetail(jobKey(projectId));
            oldTrigger = oldJob == null ? null : scheduler.getTrigger(triggerKey(projectId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not inspect the existing schedule for project '"
                    + project.getName() + "': " + e.getMessage(), e);
        }

        // Build both objects before touching the scheduler: bad cron etc. fail here with the
        // old registration still intact.
        JobDetail job = JobBuilder.newJob(SyncJob.class)
                .withIdentity(jobKey(projectId))
                .withDescription("Sync project " + project.getName())
                .usingJobData(SyncJob.PROJECT_ID, projectId)
                // The run log names the project, and the job must carry that name itself:
                // the row may already be gone by the time a fire runs.
                .usingJobData(SyncJob.PROJECT_NAME,
                        project.getName() == null ? "" : project.getName())
                .storeDurably()
                .build();
        Trigger trigger = buildTrigger(projectId, task, config);

        try {
            // Remove first so a changed interval or cron actually takes effect.
            if (oldJob != null) {
                scheduler.deleteJob(jobKey(projectId));
            }
            scheduler.scheduleJob(job, trigger);

            // The computed fire time and the zone are the two facts that explain "my cron
            // did not run when I expected": Quartz evaluates the expression in the JVM's
            // zone, so a server left on UTC silently shifts a 02:00 schedule by 8 hours.
            log.info("Scheduled sync for project '{}' ({}), next fire: {}", project.getName(),
                    describeTrigger(task, config), describeNextFire(trigger));
        } catch (SchedulerException e) {
            if (oldJob != null) {
                try {
                    scheduler.scheduleJob(oldJob, oldTrigger);
                    log.warn("Failed to replace the schedule for project '{}'; restored the"
                            + " previous registration", project.getName());
                } catch (SchedulerException restoreError) {
                    log.error("Failed to replace the schedule for project '{}' AND could not"
                            + " restore the previous registration: {}",
                            project.getName(), restoreError.getMessage());
                }
            }
            throw new IllegalStateException("Could not schedule sync for project '"
                    + project.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Builds the trigger: a cron expression when one is configured, otherwise a fixed
     * interval.
     *
     * <p>The two misfire policies differ on purpose:
     *
     * <ul>
     *   <li><b>Interval</b> discards missed fires. Catching up on a hundred skipped 2-second
     *       polls would be pure waste — the next single poll already sees everything that
     *       accumulated.</li>
     *   <li><b>Cron</b> fires the missed run once as soon as a worker is free. The fire time
     *       passes while the scheduler is paused, every thread is busy with a long cycle, or
     *       the machine is overloaded; discarding it then means a once-a-day schedule
     *       silently skips a whole day — the reported "到时间不生效". A cron the user set is
     *       a promise to run, not a polling hint.</li>
     * </ul>
     */
    private Trigger buildTrigger(Long projectId, SyncTask task, SyncConfig config) {
        String cron = config.getCronExpression() != null && !config.getCronExpression().isBlank()
                ? config.getCronExpression()
                : task.getCronExpression();

        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(projectId))
                .startNow();

        if (cron != null && !cron.isBlank()) {
            return builder.withSchedule(CronScheduleBuilder.cronSchedule(cron.trim())
                    .withMisfireHandlingInstructionFireAndProceed()).build();
        }

        long intervalMs = config.getPollIntervalMs() != null && config.getPollIntervalMs() > 0
                ? config.getPollIntervalMs()
                : properties.getPollInterval();
        // Quartz's simple schedule takes an int for milliseconds via the ms builder, so clamp
        // to a sane range: below 200ms polling is pathological, above a day use cron.
        long clamped = Math.max(200L, Math.min(intervalMs, 86_400_000L));

        return builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInMilliseconds(clamped)
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount()).build();
    }

    /**
     * The trigger's first fire time in the JVM's time zone. A trigger with no next fire time
     * is a trigger that will never run — worth seeing in the log rather than discovering by
     * waiting.
     */
    private static String describeNextFire(Trigger trigger) {
        Date next = trigger.getNextFireTime();
        if (next == null) {
            return "none";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(next.toInstant())
                + " (" + ZoneId.systemDefault().getId() + ")";
    }

    private String describeTrigger(SyncTask task, SyncConfig config) {
        String cron = config.getCronExpression() != null && !config.getCronExpression().isBlank()
                ? config.getCronExpression() : task.getCronExpression();
        if (cron != null && !cron.isBlank()) {
            return "cron: " + cron;
        }
        long interval = config.getPollIntervalMs() != null && config.getPollIntervalMs() > 0
                ? config.getPollIntervalMs() : properties.getPollInterval();
        return "every " + interval + "ms";
    }

    /**
     * Removes the polling job, pausing the project. A fire already running is not cancelled
     * here — {@link com.qqmu.jync.service.ProjectService#stop(Long)} asks it to abort through
     * the runner first; callers that only reconcile the schedule leave it to finish.
     */
    public synchronized void unschedule(Long projectId) {
        try {
            if (scheduler.checkExists(jobKey(projectId))) {
                scheduler.deleteJob(jobKey(projectId));
                log.info("Unscheduled sync for project {}", projectId);
            }
            taskStore.markStopped(projectId);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not unschedule project " + projectId + ": "
                    + e.getMessage(), e);
        }
    }

    /** Delete the polling Quartz job without touching sync_task status (CDC takes over status). */
    public synchronized void removePollingJob(Long projectId) {
        try {
            scheduler.deleteJob(jobKey(projectId));   // jobKey/group 沿用既有工具;没有 job 也无害
        } catch (SchedulerException e) {
            log.warn("Failed to remove polling job for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * When this project's polling job next runs, in the JVM's zone, or null when it is not
     * scheduled. Read by the project page: a schedule whose next fire is invisible cannot be
     * checked before the time it was supposed to run.
     */
    public java.time.LocalDateTime nextFireTime(Long projectId) {
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(projectId));
            if (trigger == null || trigger.getNextFireTime() == null) {
                return null;
            }
            return java.time.LocalDateTime.ofInstant(trigger.getNextFireTime().toInstant(),
                    ZoneId.systemDefault());
        } catch (SchedulerException e) {
            log.warn("Could not read the next fire time for project {}: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    public boolean isScheduled(Long projectId) {
        try {
            return scheduler.checkExists(jobKey(projectId));
        } catch (SchedulerException e) {
            log.warn("Could not check schedule state for project {}: {}", projectId, e.getMessage());
            return false;
        }
    }

    /** Fires the job immediately, in addition to its schedule. */
    public void triggerNow(Long projectId) {
        try {
            if (scheduler.checkExists(jobKey(projectId))) {
                scheduler.triggerJob(jobKey(projectId));
            }
        } catch (SchedulerException e) {
            log.warn("Could not trigger project {} immediately: {}", projectId, e.getMessage());
        }
    }

    /** Project ids that currently have a scheduled job, for diagnostics. */
    public List<Long> scheduledProjectIds() {
        List<Long> ids = new ArrayList<>();
        try {
            for (JobKey key : scheduler.getJobKeys(
                    org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("jync"))) {
                JobDetail detail = scheduler.getJobDetail(key);
                if (detail != null) {
                    ids.add(detail.getJobDataMap().getLong(SyncJob.PROJECT_ID));
                }
            }
        } catch (SchedulerException e) {
            log.warn("Could not enumerate scheduled jobs: {}", e.getMessage());
        }
        return ids;
    }
}
