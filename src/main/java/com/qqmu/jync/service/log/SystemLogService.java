package com.qqmu.jync.service.log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the application's own log directory for the system-log page.
 *
 * <p>Two properties matter more than the filtering: only files the rolling policy itself
 * produced are readable (name whitelist plus a canonical-path containment check, so neither
 * {@code ../} nor a symlink can escape the directory), and log text reaches the page already
 * escaped — the keyword is inserted as markup only after both sides of it were escaped.
 *
 * <p>Scanning is bounded: at most the last 16 MB of a file are read (an archive is streamed
 * from its start), the newest 1 000 matching lines are returned, and the caller is told when
 * older content was skipped. Reading from the tail matters: a live log is the most recent
 * entries, so a file longer than the budget must still show its last lines, not its first.
 */
@Service
@Slf4j
public class SystemLogService {

    static final int MAX_LINES_RETURNED = 1000;
    static final long MAX_BYTES_SCANNED = 16L * 1024 * 1024;
    static final int MAX_KEYWORD_LENGTH = 200;

    static final String ERROR_FILE = "syslog.error.file";
    static final String ERROR_READ = "syslog.error.read";

    private static final Pattern LEVEL_TOKEN =
            Pattern.compile("\\s(TRACE|DEBUG|INFO|WARN|ERROR)\\s");
    private static final List<String> LEVELS = List.of("ALL", "DEBUG", "INFO", "WARN", "ERROR");

    private final String configuredPath;
    private final long maxBytesScanned;

    @Autowired
    public SystemLogService(@Value("${logging.file.name:./logs/jync.log}") String configuredPath) {
        this(configuredPath, MAX_BYTES_SCANNED);
    }

    /** Test seam: the same reader with a smaller byte budget than a real log file. */
    SystemLogService(String configuredPath, long maxBytesScanned) {
        this.configuredPath = configuredPath;
        this.maxBytesScanned = maxBytesScanned;
    }

    /** The live file first, then the rolled archives newest-first; nothing else is offered. */
    public List<LogFile> listFiles() {
        Path dir = logDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String base = baseName();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isLogFile(path.getFileName().toString(), base))
                    .sorted(Comparator
                            .comparing((Path path) ->
                                    !path.getFileName().toString().equals(base))
                            .thenComparingLong(path -> -path.toFile().lastModified()))
                    .map(SystemLogService::toLogFile)
                    .toList();
        } catch (IOException ex) {
            log.warn("Cannot list the log directory {}: {}", dir, ex.toString());
            return List.of();
        }
    }

    /**
     * Filters one log file down to the last matching lines.
     *
     * <p>{@code fileName} may be null, meaning the live file. Returned lines contain HTML
     * markup (the keyword markers) and must be rendered with {@code th:utext}.
     */
    public LogQueryResult query(String fileName, String level, String keyword) {
        String name = StringUtils.hasText(fileName) ? fileName.trim() : baseName();
        if (name.contains("/") || name.contains("\\") || name.startsWith("..")) {
            return LogQueryResult.error(ERROR_FILE);
        }
        String base = baseName();
        if (!name.equals(base) && !(name.startsWith(base + ".") && !name.endsWith(".tmp"))) {
            return LogQueryResult.error(ERROR_FILE);
        }

        Path file = resolveInsideLogDir(name);
        if (file == null || !Files.isRegularFile(file)) {
            return LogQueryResult.error(ERROR_FILE);
        }

        String normalizedLevel = normalizeLevel(level);
        String needle = normalizeKeyword(keyword);

        Deque<String> tail = new ArrayDeque<>();
        int matched = 0;
        boolean truncated = false;
        long scannedBytes = 0;

        long startOffset = 0;
        try {
            if (!name.endsWith(".gz")) {
                long size = Files.size(file);
                if (size > maxBytesScanned) {
                    startOffset = size - maxBytesScanned;
                    truncated = true;
                }
            }
            try (BufferedReader reader = open(file, startOffset)) {
                if (startOffset > 0) {
                    // The offset can land mid-line; the fragment is not a log record.
                    reader.readLine();
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    scannedBytes += line.length() + 1L;
                    if (scannedBytes > maxBytesScanned) {
                        truncated = true;
                        break;
                    }
                    if (!matchesLevel(line, normalizedLevel)) {
                        continue;
                    }
                    if (needle != null && !containsIgnoreCase(line, needle)) {
                        continue;
                    }
                    matched++;
                    tail.addLast(render(line, needle));
                    if (tail.size() > MAX_LINES_RETURNED) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ex) {
            log.warn("Cannot read log file {}: {}", name, ex.toString());
            return LogQueryResult.error(ERROR_READ);
        }

        if (matched > tail.size()) {
            truncated = true;
        }
        return new LogQueryResult(new ArrayList<>(tail), matched, truncated, null);
    }

    private static boolean isLogFile(String name, String base) {
        return name.equals(base) || (name.startsWith(base + ".") && !name.endsWith(".tmp"));
    }

    /**
     * Resolves a whitelisted name and proves the real path still sits in the log directory;
     * a symlink planted there would otherwise read any file the process can open.
     */
    private Path resolveInsideLogDir(String name) {
        Path dir = logDir();
        Path candidate = dir.resolve(name).normalize();
        if (!dir.equals(candidate.getParent())) {
            return null;
        }
        try {
            Path realDir = dir.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realDir.equals(realFile.getParent())) {
                return null;
            }
            return realFile;
        } catch (IOException ex) {
            return null;
        }
    }

    private BufferedReader open(Path file, long offset) throws IOException {
        if (file.getFileName().toString().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
        }
        if (offset <= 0) {
            return Files.newBufferedReader(file, StandardCharsets.UTF_8);
        }
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        channel.position(offset);
        return new BufferedReader(new InputStreamReader(
                Channels.newInputStream(channel), StandardCharsets.UTF_8));
    }

    private static boolean matchesLevel(String line, String level) {
        if ("ALL".equals(level)) {
            return true;
        }
        var matcher = LEVEL_TOKEN.matcher(line);
        return matcher.find() && matcher.group(1).equals(level);
    }

    private static String normalizeLevel(String level) {
        if (!StringUtils.hasText(level)) {
            return "ALL";
        }
        String upper = level.trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(upper) ? upper : "ALL";
    }

    private static String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.length() > MAX_KEYWORD_LENGTH
                ? trimmed.substring(0, MAX_KEYWORD_LENGTH) : trimmed;
    }

    /**
     * Escapes the line piece by piece and wraps each keyword hit in {@code <mark>}. Escaping
     * before inserting markup is what keeps a log line from injecting HTML.
     */
    private static String render(String line, String keyword) {
        if (keyword == null) {
            return HtmlUtils.htmlEscape(line);
        }
        StringBuilder out = new StringBuilder(line.length() + 32);
        int from = 0;
        int hit = indexOfIgnoreCase(line, keyword, 0);
        while (hit >= 0) {
            out.append(HtmlUtils.htmlEscape(line.substring(from, hit)));
            out.append("<mark>")
                    .append(HtmlUtils.htmlEscape(line.substring(hit, hit + keyword.length())))
                    .append("</mark>");
            from = hit + keyword.length();
            hit = indexOfIgnoreCase(line, keyword, from);
        }
        out.append(HtmlUtils.htmlEscape(line.substring(from)));
        return out.toString();
    }

    private static boolean containsIgnoreCase(String line, String keyword) {
        return indexOfIgnoreCase(line, keyword, 0) >= 0;
    }

    /**
     * Case-insensitive search over the <em>original</em> string.
     *
     * <p>Searching a lower-cased copy is wrong here: a few characters change length under
     * case mapping ({@code İ} U+0130 becomes two chars), so an index from the copy addresses
     * the wrong position in the original — the highlight lands one char late, and a match at
     * the end of the line indexes past the string entirely. {@link String#regionMatches}
     * compares character by character and keeps original indices.
     */
    private static int indexOfIgnoreCase(String line, String keyword, int from) {
        int last = line.length() - keyword.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (line.regionMatches(true, i, keyword, 0, keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    private static LogFile toLogFile(Path path) {
        try {
            return new LogFile(path.getFileName().toString(), Files.size(path),
                    Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
        } catch (IOException ex) {
            return new LogFile(path.getFileName().toString(), -1L, Instant.EPOCH);
        }
    }

    private Path logDir() {
        Path configured = Paths.get(configuredPath).toAbsolutePath().normalize();
        Path parent = configured.getParent();
        return parent == null ? Paths.get(".").toAbsolutePath().normalize() : parent;
    }

    private String baseName() {
        Path configured = Paths.get(configuredPath);
        return configured.getFileName() == null ? "jync.log" : configured.getFileName().toString();
    }

    /** One readable log file, as the page's selector shows it. */
    public static final class LogFile {
        private final String name;
        private final long size;
        private final Instant modified;

        public LogFile(String name, long size, Instant modified) {
            this.name = name;
            this.size = size;
            this.modified = modified;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public Instant getModified() {
            return modified;
        }
    }

    /** Filtered tail of one file. {@code lines} already contain escaped text and markers. */
    public static final class LogQueryResult {
        private final List<String> lines;
        private final int matched;
        private final boolean truncated;
        private final String errorKey;

        public LogQueryResult(List<String> lines, int matched, boolean truncated,
                              String errorKey) {
            this.lines = lines;
            this.matched = matched;
            this.truncated = truncated;
            this.errorKey = errorKey;
        }

        static LogQueryResult error(String errorKey) {
            return new LogQueryResult(List.of(), 0, false, errorKey);
        }

        public List<String> getLines() {
            return lines;
        }

        public int getMatched() {
            return matched;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public String getErrorKey() {
            return errorKey;
        }
    }
}
