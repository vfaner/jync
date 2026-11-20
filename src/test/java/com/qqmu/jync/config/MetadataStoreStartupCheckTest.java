package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.cdc.LogCapture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The pre-Hibernate check must actually reach the log: the whole point is that the operator sees
 * the real reason before Hibernate's generic "Unable to determine Dialect" line.
 */
class MetadataStoreStartupCheckTest {

    @TempDir
    Path tempDir;

    private final MetadataStoreStartupCheck check = new MetadataStoreStartupCheck();

    @Test
    void anUnwritableStoreDirectoryIsLoggedAtError() throws IOException {
        Path store = tempDir.resolve("data/jync");
        Path dir = Files.createDirectories(store.getParent());
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(dir), "running as a user that bypasses directory permissions");

        ListAppender<ILoggingEvent> logs = LogCapture.attach(MetadataStoreStartupCheck.class);
        try {
            check.check("jdbc:h2:file:" + store + ";MODE=MySQL;AUTO_SERVER=TRUE", tempDir);
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            LogCapture.detach(MetadataStoreStartupCheck.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.ERROR, logs))
                .anyMatch(message -> message.contains("不可写") && message.contains("lock.db"));
    }

    @Test
    void aHealthyStoreLogsNothing() {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(MetadataStoreStartupCheck.class);
        try {
            check.check("jdbc:h2:file:" + tempDir.resolve("data/jync") + ";MODE=MySQL", tempDir);
        } finally {
            LogCapture.detach(MetadataStoreStartupCheck.class, logs);
        }

        assertThat(logs.list).isEmpty();
    }
}
