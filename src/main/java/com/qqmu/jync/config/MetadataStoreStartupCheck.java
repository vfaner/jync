package com.qqmu.jync.config;

import java.nio.file.Path;

import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Warns at startup when the metadata store's H2 lock file shows that another Jync instance is
 * still using it, or was killed without cleaning up.
 *
 * <p>Registered through {@code META-INF/spring.factories} as an {@code ApplicationListener}:
 * that runs after the logging system is configured (unlike an {@code EnvironmentPostProcessor})
 * but before any bean — including the datasource and Hibernate — is created. The check itself
 * only reads the lock file, never opens the store.
 */
@Slf4j
public class MetadataStoreStartupCheck implements ApplicationListener<ApplicationPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        try {
            String userDir = System.getProperty("user.dir");
            if (userDir == null) {
                return;
            }
            String url = event.getApplicationContext().getEnvironment()
                    .getProperty("spring.datasource.url");
            MetadataStoreLock.startupWarning(url, Path.of(userDir), MetadataStoreLock::serverAnswers)
                    .ifPresent(log::warn);
        } catch (RuntimeException e) {
            // A diagnostic must never be the reason a startup fails.
            log.debug("Metadata store lock check failed", e);
        }
    }
}
