package com.qqmu.jync.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * Reads the application's own log files for the /system-logs page. Everything here is about
 * trust boundaries: only files in the log directory, only the tail, and never raw log text in
 * the HTML.
 */
class SystemLogServiceTest {

    @TempDir
    Path dir;

    private Path live;

    @BeforeEach
    void setUp() {
        live = dir.resolve("jync.log");
    }

    private SystemLogService service() {
        return new SystemLogService(live.toString());
    }

    private String line(String level, String message) {
        return "2026-10-23 12:00:00.000 " + level + " [main] c.q.Test - " + message;
    }

    private void write(String content) throws IOException {
        Files.writeString(live, content, StandardCharsets.UTF_8);
    }

    private void writeGzip(Path path, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void theLiveFileIsListedFirstAndTemporaryFilesAreIgnored() throws IOException {
        write(line("INFO", "hello"));
        Path rolled = dir.resolve("jync.log.2026-10-01.0.gz");
        writeGzip(rolled, line("INFO", "old"));
        Files.writeString(dir.resolve("jync.log.tmp"), "partial");
        Files.writeString(dir.resolve("other.log"), "not ours");

        List<SystemLogService.LogFile> files = service().listFiles();

        assertThat(files).extracting(SystemLogService.LogFile::getName)
                .containsExactly("jync.log", "jync.log.2026-10-01.0.gz");
    }

    @Test
    void anArchiveIsReadThroughGzip() throws IOException {
        writeGzip(dir.resolve("jync.log.2026-10-01.0.gz"),
                line("INFO", "archived") + "\n" + line("ERROR", "boom") + "\n");

        SystemLogService.LogQueryResult result =
                service().query("jync.log.2026-10-01.0.gz", "ERROR", null);

        assertThat(result.getErrorKey()).isNull();
        assertThat(result.getLines()).hasSize(1);
        assertThat(result.getLines().get(0)).contains("boom");
    }

    @Test
    void onlyLinesOfTheRequestedLevelAreReturned() throws IOException {
        write(line("INFO", "one") + "\n" + line("WARN", "two") + "\n"
                + line("ERROR", "three") + "\n" + "  continuation of three\n");

        SystemLogService.LogQueryResult result = service().query("jync.log", "WARN", null);

        assertThat(result.getLines()).hasSize(1);
        assertThat(result.getLines().get(0)).contains("two");
    }

    @Test
    void theKeywordIsMatchedCaseInsensitively() throws IOException {
        write(line("INFO", "Connected to MySQL") + "\n" + line("INFO", "disconnected") + "\n");

        SystemLogService.LogQueryResult result = service().query("jync.log", "ALL", "mysql");

        assertThat(result.getLines()).hasSize(1);
        assertThat(result.getLines().get(0)).contains("MySQL");
    }

    @Test
    void onlyTheLastThousandMatchingLinesAreReturned() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 3000; i++) {
            content.append(line("INFO", "line-" + i)).append('\n');
        }
        write(content.toString());

        // "INFO" matches every line without splitting the line numbers the assertions look at.
        SystemLogService.LogQueryResult result = service().query("jync.log", "ALL", "INFO");

        assertThat(result.getLines()).hasSize(1000);
        assertThat(result.getLines().get(0)).contains("line-2001");
        assertThat(result.getLines().get(999)).contains("line-3000");
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getMatched()).isEqualTo(3000);
    }

    @Test
    void theNewestLinesSurviveWhenTheFileHasMoreLinesThanTheScanBudget() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 25_000; i++) {
            content.append(line("ERROR", "line-" + i)).append('\n');
        }
        write(content.toString());

        SystemLogService.LogQueryResult result = service().query("jync.log", "ERROR", null);

        assertThat(result.getLines()).hasSize(1000);
        assertThat(result.getLines().get(999)).contains("line-25000");
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void aFileLargerThanTheByteBudgetIsReadFromItsTail() throws IOException {
        SystemLogService smallBudget = new SystemLogService(live.toString(), 4096);
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            content.append(line("INFO", "line-" + i)).append('\n');
        }
        write(content.toString());

        SystemLogService.LogQueryResult result = smallBudget.query("jync.log", "ALL", null);

        assertThat(result.getLines()).isNotEmpty();
        assertThat(result.getLines().get(result.getLines().size() - 1)).contains("line-500");
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void aNameOutsideTheLogDirectoryIsRefused() throws IOException {
        write(line("INFO", "hello"));
        Files.writeString(dir.getParent().resolve("secret.txt"), "secret");

        SystemLogService.LogQueryResult result = service().query("../secret.txt", "ALL", null);

        assertThat(result.getErrorKey()).isEqualTo("syslog.error.file");
        assertThat(result.getLines()).isEmpty();
    }

    @Test
    void anUnknownFileInTheDirectoryIsRefused() throws IOException {
        write(line("INFO", "hello"));
        Files.writeString(dir.resolve("other.log"), "not ours");

        assertThat(service().query("other.log", "ALL", null).getErrorKey())
                .isEqualTo("syslog.error.file");
    }

    @Test
    void logTextIsEscapedAndTheKeywordIsMarked() throws IOException {
        write(line("INFO", "<script>alert(\"boom\")</script> needle here"));

        SystemLogService.LogQueryResult result = service().query("jync.log", "ALL", "needle");

        String rendered = result.getLines().get(0);
        assertThat(rendered).doesNotContain("<script>");
        assertThat(rendered).contains("&lt;script&gt;");
        assertThat(rendered).contains("<mark>needle</mark>");
    }

    @Test
    void aKeywordThatLooksLikeHtmlIsEscapedInsideTheMark() throws IOException {
        write(line("INFO", "value <b> here"));

        String rendered = service().query("jync.log", "ALL", "<b>").getLines().get(0);

        assertThat(rendered).contains("<mark>&lt;b&gt;</mark>");
        assertThat(rendered).doesNotContain("<b>");
    }

    @Test
    void noMatchIsAnEmptySuccessfulResult() throws IOException {
        write(line("INFO", "hello"));

        SystemLogService.LogQueryResult result = service().query("jync.log", "ERROR", null);

        assertThat(result.getErrorKey()).isNull();
        assertThat(result.getLines()).isEmpty();
        assertThat(result.getMatched()).isZero();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void aMissingLogFileIsReportedNotThrown() {
        SystemLogService.LogQueryResult result = service().query("jync.log", "ALL", null);

        assertThat(result.getErrorKey()).isEqualTo("syslog.error.file");
        assertThat(result.getLines()).isEmpty();
    }
}
