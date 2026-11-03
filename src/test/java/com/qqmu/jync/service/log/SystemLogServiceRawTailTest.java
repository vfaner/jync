package com.qqmu.jync.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The diagnostics bundle needs the same whitelist and gzip handling as the log page, but
 * without the HTML escaping: its output goes into a text file, not {@code th:utext}.
 */
class SystemLogServiceRawTailTest {

    @TempDir
    Path dir;

    private SystemLogService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("jync.log"),
                "first <b> & line\nsecond\nthird\n", StandardCharsets.UTF_8);
        service = new SystemLogService(dir.resolve("jync.log").toString(),
                SystemLogService.MAX_BYTES_SCANNED);
    }

    @Test
    void returnsTheLastLinesWithoutHtmlEscaping() {
        List<String> tail = service.rawTail(2, 1024 * 1024);

        assertThat(tail).containsExactly("second", "third");
    }

    @Test
    void keepsMarkupCharactersVerbatim() {
        assertThat(service.rawTail(3, 1024 * 1024))
                .containsExactly("first <b> & line", "second", "third");
    }

    @Test
    void rejectsNamesOutsideTheLogDirectory() {
        assertThat(service.rawTail("../../etc/passwd", 10, 1024)).isEmpty();
    }

    @Test
    void rejectsTemporaryFiles() throws Exception {
        Files.writeString(dir.resolve("jync.log.2026-01-01.0.tmp"), "secret\n");

        assertThat(service.rawTail("jync.log.2026-01-01.0.tmp", 10, 1024)).isEmpty();
    }

    @Test
    void readsRolledGzipArchives() throws Exception {
        Path gz = dir.resolve("jync.log.2026-01-01.0.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("archived-a\narchived-b\n".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(service.rawTail("jync.log.2026-01-01.0.gz", 10, 1024 * 1024))
                .containsExactly("archived-a", "archived-b");
    }

    @Test
    void boundsTheScanByTheByteBudget() throws Exception {
        Files.writeString(dir.resolve("jync.log"),
                "old\n" + "x".repeat(10_000) + "\nnew\n", StandardCharsets.UTF_8);

        List<String> tail = service.rawTail(10, 100);

        assertThat(tail).contains("new");
        assertThat(tail).doesNotContain("old");
    }
}
