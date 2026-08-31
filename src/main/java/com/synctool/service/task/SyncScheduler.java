package com.synctool.service.task;

import java.util.ArrayList;
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

import com.synctool.config.SyncProperties;
import com.synctool.dto.SyncConfig;
import com.synctool.model.Project;
import com.synctool.model.SyncTask;

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
        return JobKey.jobKey("sync-job-" + projectId, "synctool");
    }

    private TriggerKey triggerKey(Long projectId) {
        return TriggerKey.triggerKey("sync-trigger-" + projectId, "synctool");
    }

    /**
     * Makes the schedule match the project's enabled flag.
     *
     * <p>Safe to call repeatedly: an existing job is replaced rather than duplicated.
     */
    public void reschedule(Project project) {
        if (Boolean.TRUE.equals(project.getEnabled())) {
            schedule(project);
        } else {
            unschedule(project.getId());
        }
    }

    /** Registers the polling job, replacing any previous registration. */
    public void schedule(Project project) {
        Long projectId = project.getId();
        SyncTask task = taskStore.ensureTask(project);
        SyncConfig config = contextFactory.parseConfig(project);

        try {
            // Remove first so a changed interval or cron actually takes effect.
            if (scheduler.checkExists(jobKey(projectId))) {
                scheduler.deleteJob(jobKey(projectId));
            }

            JobDetail job = JobBuilder.newJob(SyncJob.class)
                    .withIdentity(jobKey(projectId))
                    .withDescription("Sync project " + project.getName())
                    .usingJobData(SyncJob.PROJECT_ID, projectId)
                    .storeDurably()
                    .build();

            Trigger trigger = buildTrigger(projectId, task, config);
            scheduler.scheduleJob(job, trigger);

            log.info("Scheduled sync for project '{}' ({})", project.getName(),
                    describeTrigger(task, config));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Could not schedule sync for project '"
                    + project.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Builds the trigger: a cron expression when one is configured, otherwise a fixed
     * interval.
     *
     * <p>Both use a misfire policy that discards missed fires rather than replaying them. For
     * a polling sync, catching up on a hundred skipped 2-second fires would be pure waste —
     * the next single poll already sees everything that accumulated.
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
                    .withMisfireHandlingInstructionDoNothing()).build();
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

    /** Removes the polling job, pausing the project. In-flight cycles finish on their own. */
    public void unschedule(Long projectId) {
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
                    org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("synctool"))) {
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
