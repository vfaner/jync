package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The file log is the only unbounded-growth surface left after the idle-cycle lines moved to
 * DEBUG. Spring Boot's default rolling policy keeps any amount of history for 7 days; on a
 * long-running deployment the archives would still be tens of GB. The shipped configuration
 * must name an absolute cap and clean old archives on start.
 */
class LoggingRollingPolicyConfigTest {

    @Test
    void theFileLogHasASizeCapAndStartupCleanup() throws IOException {
        PropertySource<?> yaml = loadApplicationYaml();

        assertThat(yaml.getProperty("logging.file.name")).isEqualTo("./logs/jync.log");
        assertThat(yaml.getProperty("logging.logback.rollingpolicy.max-file-size"))
                .isEqualTo("10MB");
        assertThat(yaml.getProperty("logging.logback.rollingpolicy.max-history"))
                .isEqualTo(14);
        assertThat(yaml.getProperty("logging.logback.rollingpolicy.total-size-cap"))
                .isEqualTo("500MB");
        assertThat(yaml.getProperty("logging.logback.rollingpolicy.clean-history-on-start"))
                .isEqualTo(true);
    }

    private static PropertySource<?> loadApplicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        return sources.get(0);
    }
}
