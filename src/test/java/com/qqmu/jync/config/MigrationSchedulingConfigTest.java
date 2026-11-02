package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MigrationSchedulingConfigTest {

    @Test
    void theRunExecutorIsSmallNamedAndDoesNotWaitOnShutdown() {
        MigrationSchedulingConfig config = new MigrationSchedulingConfig();

        ThreadPoolTaskExecutor executor = config.migrationExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("jync-migration-run-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(50);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void onlyTheRunExecutorIsExposedAsABean() {
        // Exposing the trigger pool as a TaskScheduler bean would make @Scheduled use it for
        // CDC supervision and the schedule reconciler.
        String[] beans = Arrays.stream(MigrationSchedulingConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .toArray(String[]::new);

        assertThat(beans).containsExactly("migrationExecutor");
    }
}
