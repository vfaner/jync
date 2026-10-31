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

import com.qqmu.jync.model.EtlTask;
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
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public EtlScheduler(EtlTaskRepository taskRepository, EtlTaskService taskService) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
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
        for (EtlTask task : taskRepository.findByEnabledTrue()) {
            refresh(task.getId());
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

    /** Converges the registered trigger with the task's current enabled flag and cron. */
    public synchronized void refresh(Long taskId) {
        cancel(taskId);
        EtlTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !Boolean.TRUE.equals(task.getEnabled())) {
            return;
        }
        String cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) {
            return;
        }
        try {
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> taskService.submit(taskId), new CronTrigger(cron.trim()));
            scheduled.put(taskId, future);
            log.info("ETL task {} scheduled with cron {}", taskId, cron.trim());
        } catch (RuntimeException e) {
            log.warn("ETL task {} could not be scheduled: {}", taskId, e.getMessage());
        }
    }

    private void cancel(Long taskId) {
        ScheduledFuture<?> future = scheduled.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
