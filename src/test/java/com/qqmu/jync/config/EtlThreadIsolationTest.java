package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;

/**
 * ETL runs on threads of its own, but those threads must not become the threads that run
 * everything else.
 *
 * <p>{@code @Scheduled} methods — the CDC supervisor, the schedule reconciler and the change-log
 * pruner — resolve the application's single {@code TaskScheduler} bean by type. Registering the
 * ETL trigger pool as a bean therefore makes Spring Boot back off from creating its own
 * {@code taskScheduler}, and quietly moves those existing jobs onto ETL's pool. That is exactly
 * the coupling this feature promised not to introduce.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:etlisolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class EtlThreadIsolationTest {

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("the ETL pool does not displace the application's own task scheduler")
    void theEtlSchedulerIsNotTheApplicationTaskScheduler() {
        Map<String, TaskScheduler> schedulers = context.getBeansOfType(TaskScheduler.class);

        assertThat(schedulers.keySet())
                .as("the @Scheduled methods need Boot's taskScheduler, so it must still exist")
                .contains("taskScheduler");
        assertThat(schedulers.keySet())
                .as("the ETL trigger pool must not be a TaskScheduler bean")
                .noneMatch(name -> name.toLowerCase().contains("etl"));
    }
}
