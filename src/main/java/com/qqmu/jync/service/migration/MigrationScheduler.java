package com.qqmu.jync.service.migration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationTaskRepository;

/**
 * Registers each enabled migration task's cron on a scheduler of its own.
 *
 * <p>The trigger pool is owned here rather than declared as a bean: exposing a
 * {@code TaskScheduler} would make Spring's {@code @Scheduled} processor use it for the CDC
 * supervisor and the schedule reconciler, which must keep their real-time threads.
 */
@Service
public class MigrationScheduler {

    private static final Logger log = LoggerFactory.getLogger(MigrationScheduler.class);

    private final ThreadPoolTaskScheduler scheduler = createTriggerPool();
    private final MigrationTaskRepository taskRepository;
    private final MigrationTaskService taskService;
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public MigrationScheduler(MigrationTaskRepository taskRepository,
                              MigrationTaskService taskService) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
    }

    private static ThreadPoolTaskScheduler createTriggerPool() {
        ThreadPoolTaskScheduler triggerPool = new ThreadPoolTaskScheduler();
        triggerPool.setPoolSize(2);
        triggerPool.setThreadNamePrefix("jync-migration-trigger-");
        triggerPool.setWaitForTasksToCompleteOnShutdown(false);
        triggerPool.setRemoveOnCancelPolicy(true);
        triggerPool.initialize();
        return triggerPool;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void scheduleAllOnStartup() {
        for (MigrationType type : MigrationType.values()) {
            for (MigrationTask task : taskRepository.findByEnabledTrueAndType(type)) {
                refresh(task.getId());
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTaskChanged(MigrationTaskChangedEvent event) {
        if (event.deleted()) {
            cancel(event.taskId());
        } else {
            refresh(event.taskId());
        }
    }

    /** Converges the registered trigger with the task's current enabled flag and cron. */
    public synchronized void refresh(Long taskId) {
        cancel(taskId);
        MigrationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !shouldSchedule(task)) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> trigger(taskId), new CronTrigger(task.getCronExpression().trim()));
            scheduled.put(taskId, future);
            log.info("Dedicated migration {} scheduled with cron {}", taskId,
                    task.getCronExpression().trim());
        } catch (RuntimeException e) {
            log.warn("Dedicated migration {} could not be scheduled: {}", taskId, e.getMessage());
        }
    }

    static boolean shouldSchedule(MigrationTask task) {
        return Boolean.TRUE.equals(task.getEnabled())
                && task.getCronExpression() != null
                && !task.getCronExpression().isBlank();
    }

    /** One cron firing; a still-running previous run is skipped, not queued twice. */
    void trigger(Long taskId) {
        try {
            if (!taskService.submit(taskId, MigrationRunTrigger.SCHEDULE)) {
                log.info("Dedicated migration {} is still running; the scheduled run was skipped",
                        taskId);
            }
        } catch (RuntimeException e) {
            // A rejected submission must not kill the scheduler thread.
            log.warn("Dedicated migration {} could not be started: {}", taskId, e.getMessage());
        }
    }

    private void cancel(Long taskId) {
        ScheduledFuture<?> future = scheduled.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    // --- package-private inspection for tests -------------------------------------------

    int scheduledCount() {
        return scheduled.size();
    }

    boolean isScheduled(Long taskId) {
        return scheduled.containsKey(taskId);
    }

    ScheduledFuture<?> scheduledFuture(Long taskId) {
        return scheduled.get(taskId);
    }
}
