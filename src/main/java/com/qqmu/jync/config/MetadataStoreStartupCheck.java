package com.qqmu.jync.config;

import java.nio.file.Path;

import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Two pre-Hibernate checks on the metadata store:
 *
 * <ul>
 *   <li>{@link MetadataStorePreflight} logs an ERROR when the running user cannot write the H2
 *       store's directory or file — the case where H2 fails to create its {@code .lock.db} and
 *       the operator otherwise only sees Hibernate's generic "Unable to determine Dialect"
 *       with no lock file to explain it;</li>
 *   <li>{@link MetadataStoreLock} warns when a lock file shows another Jync instance still uses
 *       the store, or a killed process left the lock behind.</li>
 * </ul>
 *
 * <p>Registered through {@code META-INF/spring.factories} as an {@code ApplicationListener}:
 * that runs after the logging system is configured (unlike an {@code EnvironmentPostProcessor})
 * but before any bean — including the datasource and Hibernate — is created. The checks only
 * read the filesystem, never open the store.
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
            check(url, Path.of(userDir));
        } catch (RuntimeException e) {
            // A diagnostic must never be the reason a startup fails.
            log.debug("Metadata store check failed", e);
        }
    }

    /** Both checks read the filesystem only; the working directory is injectable for tests. */
    void check(String url, Path workingDirectory) {
        MetadataStorePreflight.startupProblem(url, workingDirectory).ifPresent(log::error);
        MetadataStoreLock.startupWarning(url, workingDirectory, MetadataStoreLock::serverAnswers)
                .ifPresent(log::warn);
    }
}
