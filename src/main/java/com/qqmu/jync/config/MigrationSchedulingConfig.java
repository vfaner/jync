package com.qqmu.jync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated threads for dedicated migrations, separate from real-time sync and ETL.
 *
 * <p>A migration can copy an index for an hour; sharing the ETL pool would let it queue
 * behind (or ahead of) an operator's SQL load. Only the run executor is a bean — the cron
 * trigger pool lives inside {@code MigrationScheduler} so {@code @Scheduled} cannot adopt it.
 */
@Configuration
public class MigrationSchedulingConfig {

    /** Runs manual and scheduled migrations, at most two at a time. */
    @Bean(name = "migrationExecutor")
    public ThreadPoolTaskExecutor migrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("jync-migration-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
