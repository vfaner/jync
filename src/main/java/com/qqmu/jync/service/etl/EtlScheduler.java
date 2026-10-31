package com.qqmu.jync.service.etl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;

/**
 * Registers ETL cron triggers on a scheduler of its own.
 *
 * <p>Nothing here touches Quartz or the real-time sync engine: an ETL job can run for as long
 * as an operator's SQL takes, and it must not occupy a thread the poll loop needs.
 *
 * <p>The pool is owned here rather than declared as a bean. Spring's {@code @Scheduled}
 * processor takes the application's single {@code TaskScheduler} bean, so an ETL scheduler bean
 * would also become the one running the CDC supervisor and the schedule reconciler — the exact
 * coupling this class exists to avoid.
 */
@Service
public class EtlScheduler {

    private static final Logger log = LoggerFactory.getLogger(EtlScheduler.class);

    private final ThreadPoolTaskScheduler scheduler = createTriggerPool();
    private final EtlTaskRepository taskRepository;
    private final EtlTaskService taskService;
    private final EtlBatchRepository batchRepository;
    private final EtlBatchService batchService;
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> batchScheduled = new ConcurrentHashMap<>();

    public EtlScheduler(EtlTaskRepository taskRepository, EtlTaskService taskService,
                        EtlBatchRepository batchRepository, EtlBatchService batchService) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.batchRepository = batchRepository;
        this.batchService = batchService;
    }

    private static ThreadPoolTaskScheduler createTriggerPool() {
        ThreadPoolTaskScheduler triggerPool = new ThreadPoolTaskScheduler();
        triggerPool.setPoolSize(2);
        triggerPool.setThreadNamePrefix("jync-etl-trigger-");
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
    public void scheduleAllOnStartup() {
        // Members never schedule themselves: the batch trigger runs them. Loading them here
        // would also make a member with a stale enabled flag fight its batch's schedule.
        for (EtlTask task : taskRepository.findByEnabledTrueAndBatchIdIsNull()) {
            refresh(task.getId());
        }
        for (EtlBatch batch : batchRepository.findByEnabledTrue()) {
            refreshBatch(batch.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTaskChanged(EtlTaskChangedEvent event) {
        if (event.deleted()) {
            cancel(event.taskId());
        } else {
            refresh(event.taskId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onBatchChanged(EtlBatchChangedEvent event) {
        if (event.deleted()) {
            cancelBatch(event.batchId());
        } else {
            refreshBatch(event.batchId());
        }
    }

    /** Converges the registered trigger with the task's current enabled flag and cron. */
    public synchronized void refresh(Long taskId) {
        cancel(taskId);
        EtlTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !shouldSchedule(task)) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> trigger(taskId), new CronTrigger(task.getCronExpression().trim()));
            scheduled.put(taskId, future);
            log.info("ETL task {} scheduled with cron {}", taskId, task.getCronExpression().trim());
        } catch (RuntimeException e) {
            log.warn("ETL task {} could not be scheduled: {}", taskId, e.getMessage());
        }
    }

    /** Converges the registered trigger with the batch's current enabled flag and cron. */
    public synchronized void refreshBatch(Long batchId) {
        cancelBatch(batchId);
        EtlBatch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !shouldSchedule(batch)) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> triggerBatch(batchId), new CronTrigger(batch.getCronExpression().trim()));
            batchScheduled.put(batchId, future);
            log.info("ETL batch {} scheduled with cron {}", batchId, batch.getCronExpression().trim());
        } catch (RuntimeException e) {
            log.warn("ETL batch {} could not be scheduled: {}", batchId, e.getMessage());
        }
    }

    /**
     * A member's schedule belongs to its batch, so a member row never registers a trigger of
     * its own even if an old row still carries an enabled flag.
     */
    static boolean shouldSchedule(EtlTask task) {
        return task.getBatchId() == null
                && Boolean.TRUE.equals(task.getEnabled())
                && cronPresent(task.getCronExpression());
    }

    static boolean shouldSchedule(EtlBatch batch) {
        return Boolean.TRUE.equals(batch.getEnabled())
                && cronPresent(batch.getCronExpression());
    }

    private static boolean cronPresent(String cron) {
        return cron != null && !cron.isBlank();
    }

    /** One cron firing. */
    void trigger(Long taskId) {
        try {
            if (!taskService.submit(taskId)) {
                // The previous run has not finished. Skipping is right — a second copy would
                // race it — but the operator should be able to see why this firing did nothing.
                log.info("ETL task {} is still running; the scheduled run was skipped", taskId);
            }
        } catch (RuntimeException e) {
            // The scheduler thread only logs what the trigger throws, and a rejected
            // submission (queue full, executor shutting down) would otherwise vanish.
            log.warn("ETL task {} could not be started: {}", taskId, e.getMessage());
        }
    }

    /** One cron firing of a batch. */
    void triggerBatch(Long batchId) {
        try {
            if (!batchService.submit(batchId)) {
                log.info("ETL batch {} is still running; the scheduled run was skipped", batchId);
            }
        } catch (RuntimeException e) {
            log.warn("ETL batch {} could not be started: {}", batchId, e.getMessage());
        }
    }

    boolean isScheduled(Long taskId) {
        return scheduled.containsKey(taskId);
    }

    boolean isScheduledBatch(Long batchId) {
        return batchScheduled.containsKey(batchId);
    }

    private void cancel(Long taskId) {
        ScheduledFuture<?> future = scheduled.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelBatch(Long batchId) {
        ScheduledFuture<?> future = batchScheduled.remove(batchId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
