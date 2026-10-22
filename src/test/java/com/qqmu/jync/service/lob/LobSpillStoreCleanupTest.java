package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery for the spill directory: a JVM killed mid-transaction leaves
 * {@code jync-lob-*} files nobody will ever release. Startup sweeps them by age, and
 * shutdown closes whatever sessions are still open. Both must leave other applications'
 * files alone — the directory is configurable and may be shared.
 */
class LobSpillStoreCleanupTest {

    @TempDir
    Path dir;

    @Test
    void sweepDeletesOnlyStaleFilesWithTheSpillPrefix() throws IOException {
        Path stale = Files.createTempFile(dir, LobSpillSession.FILE_PREFIX, ".lob");
        Path fresh = Files.createTempFile(dir, LobSpillSession.FILE_PREFIX, ".lob");
        Path foreign = Files.writeString(dir.resolve("someone-elses.tmp"), "x");
        Files.setLastModifiedTime(stale, old());
        Files.setLastModifiedTime(foreign, old());

        int deleted = LobSpillStore.of(dir, 1 << 20).sweepOldFiles(24);

        assertThat(deleted).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        assertThat(foreign).exists();
    }

    @Test
    void aNonPositiveRetentionDisablesTheSweep() throws IOException {
        Path stale = Files.createTempFile(dir, LobSpillSession.FILE_PREFIX, ".lob");
        Files.setLastModifiedTime(stale, old());

        assertThat(LobSpillStore.of(dir, 1 << 20).sweepOldFiles(0)).isZero();
        assertThat(stale).exists();
    }

    @Test
    void closingEveryActiveSessionDeletesTheirFiles() throws IOException {
        LobSpillStore store = LobSpillStore.of(dir, 1 << 20);
        LobSpillSession first = store.open();
        LobSpillSession second = store.open();
        try {
            first.spillText(new java.io.StringReader("a".repeat(100)));
            second.spillText(new java.io.StringReader("b".repeat(100)));

            LobSpillStore.closeAllActiveSessions();

            assertThat(spillFiles()).isZero();
            assertThat(store.activeSessions()).isZero();
        } finally {
            first.close();
            second.close();
        }
    }

    private long spillFiles() {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString()
                    .startsWith(LobSpillSession.FILE_PREFIX)).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileTime old() {
        return FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS));
    }
}
