package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The node id has to survive a restart: a per-JVM random value would make every run a crashed
 * process left behind look like "another node owns it", so startup recovery would never mark
 * it INTERRUPTED and the run would stay RUNNING forever.
 */
class MigrationNodeIdTest {

    @Test
    void theStoredIdSurvivesARestart(@TempDir Path dir) {
        String file = dir.resolve("node-id").toString();

        String first = new MigrationNodeId("", file).id();
        String second = new MigrationNodeId("", file).id();

        assertThat(first).isNotBlank();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anExplicitConfigurationWinsAndIsTrimmed(@TempDir Path dir) {
        assertThat(new MigrationNodeId("  node-7  ", dir.resolve("node-id").toString()).id())
                .isEqualTo("node-7");
    }

    @Test
    void anUnwritableDataDirectoryFallsBackInsteadOfFailingStartup(@TempDir Path dir)
            throws Exception {
        // The parent of the id file is a regular file, so the write cannot succeed.
        Path blocker = dir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "x");

        MigrationNodeId node = new MigrationNodeId("", blocker.resolve("node-id").toString());

        assertThat(node.id()).isNotBlank();
    }
}
