package com.qqmu.jync.service.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.util.LogPurgeDates;

/**
 * The system-log date purge deletes rolled archives only: the live file is still being
 * written and a partial rewrite would race the appender; .tmp intermediates and names without
 * a rolling date are not the rolling policy's product and stay. The selected day is included.
 */
class SystemLogServicePurgeTest {

    @TempDir Path dir;

    private SystemLogService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SystemLogService(dir.resolve("jync.log").toString(), 1024);
        Files.writeString(dir.resolve("jync.log"), "live line\n");
        Files.writeString(dir.resolve("jync.log.2026-11-01.0.gz"), "old");
        Files.writeString(dir.resolve("jync.log.2026-11-10.0.gz"), "cutoff day");
        Files.writeString(dir.resolve("jync.log.2026-11-20.0.gz"), "newer");
        Files.writeString(dir.resolve("jync.log.2026-11-20.0.gz.tmp"), "intermediate");
        Files.writeString(dir.resolve("notes.txt"), "not a log");
    }

    @Test
    void deletesArchivesOnOrBeforeTheSelectedDayAndNothingElse() {
        int removed = service.purgeBefore(LocalDate.of(2026, 11, 10));

        assertThat(removed).isEqualTo(2);
        assertThat(Files.exists(dir.resolve("jync.log"))).isTrue();
        assertThat(Files.exists(dir.resolve("jync.log.2026-11-01.0.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve("jync.log.2026-11-10.0.gz"))).isFalse();
        assertThat(Files.exists(dir.resolve("jync.log.2026-11-20.0.gz"))).isTrue();
        assertThat(Files.exists(dir.resolve("jync.log.2026-11-20.0.gz.tmp"))).isTrue();
        assertThat(Files.exists(dir.resolve("notes.txt"))).isTrue();
    }

    @Test
    void aDayBeforeEveryArchiveDeletesNothing() {
        assertThat(service.purgeBefore(LocalDate.of(2026, 10, 31))).isZero();
        assertThat(Files.exists(dir.resolve("jync.log.2026-11-01.0.gz"))).isTrue();
    }

    @Test
    void todayIsRefused() {
        assertThatThrownBy(() -> service.purgeBefore(LocalDate.now(ZoneId.systemDefault())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LogPurgeDates.INVALID_DATE);
    }

    @Test
    void aMissingDirectoryIsAnEmptyPurgeNotAFailure() throws Exception {
        SystemLogService absent =
                new SystemLogService(dir.resolve("absent/jync.log").toString(), 1024);

        assertThat(absent.purgeBefore(LocalDate.of(2026, 11, 10))).isZero();
    }
}
