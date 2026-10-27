package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The scheduling keys only work under their own prefixes. A {@code properties} block that
 * slips under {@code spring.task} instead of {@code spring.quartz} is not rejected by the
 * binder — it is silently ignored, and Quartz falls back to its defaults (a different
 * scheduler instance name), which nobody notices until a deployment looks different.
 */
class ApplicationYamlSchedulingTest {

    private static PropertySource<?> applicationYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }

    @Test
    void quartzSettingsBindUnderSpringQuartz() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertThat(yaml.getProperty("spring.quartz.job-store-type")).isEqualTo("memory");
        assertThat(yaml.getProperty("spring.quartz.properties.org.quartz.scheduler.instanceName"))
                .isEqualTo("jync-scheduler");
        assertThat(yaml.getProperty("spring.quartz.properties.org.quartz.threadPool.threadCount"))
                .isEqualTo("10");
        assertThat(yaml.containsProperty("spring.task.properties"))
                .as("Spring Boot has no spring.task.properties; the key would be dead")
                .isFalse();
    }

    @Test
    void schedulerPoolIsWidenedForTheScheduledTasks() throws IOException {
        assertThat(applicationYaml().getProperty("spring.task.scheduling.pool.size"))
                .isEqualTo(4);
    }
}
