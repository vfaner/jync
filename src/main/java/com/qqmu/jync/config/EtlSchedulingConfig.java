package com.qqmu.jync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated threads for ETL, kept away from the Quartz scheduler that runs real-time sync.
 *
 * <p>The real-time engine polls on a short interval and shares one ten-thread Quartz pool;
 * an hour-long ETL load in that pool would delay or starve synchronization. These pools are
 * therefore separate, small and named so the cause of a busy thread is obvious in a dump.
 *
 * <p>Only the run executor is a bean. The cron trigger pool lives inside {@code EtlScheduler}
 * on purpose: {@code @Scheduled} resolves the application's single {@code TaskScheduler} bean by
 * type, so exposing the ETL pool as one would silently move the CDC supervisor, the schedule
 * reconciler and the log pruner onto ETL's threads.
 */
@Configuration
public class EtlSchedulingConfig {

    /** Runs manual and scheduled ETL jobs, at most two at a time. */
    @Bean(name = "etlExecutor")
    public ThreadPoolTaskExecutor etlExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("jync-etl-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
