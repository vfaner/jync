package com.qqmu.jync.service.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.qqmu.jync.config.I18nConfig;
import com.qqmu.jync.service.version.ReleaseFeed.Asset;
import com.qqmu.jync.service.version.ReleaseFeed.Release;
import com.qqmu.jync.service.version.ReleaseFeed.Source;
import com.qqmu.jync.service.version.SourceAwareFeed;
import com.qqmu.jync.service.version.VersionService;

import lombok.extern.slf4j.Slf4j;

/**
 * One-click in-app upgrade: download the fixed-name {@code jync.jar} release asset from the
 * user-chosen mirror, verify it, swap it in place of the running jar and offer a restart.
 *
 * <p>The swap is a rename dance ({@code jync.jar → jync.jar.bak}, {@code jync.jar.part →
 * jync.jar}) because every platform we support — Windows included — allows <em>renaming</em> a
 * jar the JVM has open, while writing through the open handle is not safe. The old jar stays
 * as {@code .bak} and a marker file ({@code .jync-update.properties}) records the installed
 * target version, so:
 * <ul>
 *   <li>a fresh bean (page reload, restart between download and reboot) rebuilds READY from
 *       the marker instead of losing the downloaded upgrade;</li>
 *   <li>{@link #cleanupAfterBoot()} deletes backup/marker/scripts only once the process
 *       actually boots on the target version — after a failed boot or a rollback the backup
 *       survives;</li>
 *   <li>the restart helper script can roll the backup over the jar if the new process never
 *       becomes ready.</li>
 * </ul>
 *
 * <p>Everything with a side effect outside this class — the HTTP download, launching the
 * helper script, the JVM exit — sits behind an injected seam
 * ({@link JarDownloader}/{@link RestartLauncher}/{@link Shutdown}) so the flow is testable
 * without sockets, processes or {@code System.exit}.
 */
@Slf4j
public class UpgradeService {

    /** Fixed release asset name; the version lives in the tag, never in the file name. */
    public static final String JAR_NAME = "jync.jar";
    static final String BACKUP_SUFFIX = ".bak";
    static final String PART_SUFFIX = ".part";
    static final String MARKER_NAME = ".jync-update.properties";
    static final String SHELL_SCRIPT_NAME = "jync-update-restart.sh";
    static final String PS1_SCRIPT_NAME = "jync-update-restart.ps1";
    /** Delay before the JVM exits after the helper script was launched. */
    static final long EXIT_DELAY_MS = 3_000L;

    public enum Phase { IDLE, DOWNLOADING, READY, FAILED, RESTARTING }

    /**
     * A message that has not been resolved yet: the key plus the arguments to substitute.
     *
     * <p>Progress messages are produced on the download thread, which has no request and
     * therefore no locale, and read by whoever happens to be polling. Collecting the key here
     * and rendering it at the point of reading is what lets two admins on different languages
     * each see their own — the same reason {@link #status()} returns a snapshot rather than
     * pushing text.
     */
    public record Localized(String key, Object... args) {
        /** Logs only; the reader's rendering comes from the bundle. */
        @Override
        public String toString() {
            return args.length == 0 ? key : key + " " + Arrays.toString(args);
        }
    }

    /**
     * A failed check that already knows its wording, minus a locale. Carried on the exception
     * because the checks that raise it run on the download thread: by the time the catch block
     * sees it, the only locale in scope is the wrong one.
     */
    static class LocalizedFailure extends IOException {
        private final Localized message;

        LocalizedFailure(Localized message, Throwable cause) {
            super(message.toString(), cause);
            this.message = message;
        }

        Localized message() {
            return message;
        }
    }

    /**
     * Numbers that appear inside a message travel as text. {@code MessageFormat} localises a
     * numeric argument, so a byte count rendered as {@code {0}} comes out as "1,024" -- different
     * digits in some locales, and not what this said before it was a message. Sizes, hashes,
     * HTTP codes and ports are values to read off, not prose to localise.
     */

    /** Immutable snapshot for polling: {@code bytesRead/bytesTotal} drive the progress bar. */
    public record Status(Phase phase, Localized message, String targetVersion, String source,
                         long bytesRead, long bytesTotal) {
        public static Status idle() {
            return new Status(Phase.IDLE, null, null, null, 0, 0);
        }
    }

    /** Downloads {@code url} to {@code target}, reporting bytes read so far. */
    public interface JarDownloader {
        void download(String url, Path target, Long expectedBytes, LongConsumer progress)
                throws Exception;
    }

    /** Detaches the platform restart helper script. */
    public interface RestartLauncher {
        void launch(Path script, Path workDir) throws Exception;
    }

    /** Requests a delayed JVM exit (Spring's shutdown hooks run on exit). */
    public interface Shutdown {
        void exit(long delayMillis);
    }

    /** Everything the generated helper scripts need, as plain strings. */
    public record RestartSpec(long oldPid, String javaBinary, String jarPath, String backupPath,
                              String workDir, Integer port, String failedPath) {
    }

    private final Supplier<String> currentVersion;
    private final SourceAwareFeed feed;
    private final JarDownloader downloader;
    private final RestartLauncher launcher;
    private final Shutdown shutdown;
    private final Supplier<Path> jarLocation;
    private final Supplier<Path> workDir;
    private final String port;
    private final I18nConfig.MessageSourceAccessorHolder messages;

    /** Single in-flight upgrade: start() flips it, the terminal phase clears it. */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Status status = Status.idle();

    public UpgradeService(Supplier<String> currentVersion, SourceAwareFeed feed,
                          JarDownloader downloader, RestartLauncher launcher, Shutdown shutdown,
                          Supplier<Path> jarLocation, Supplier<Path> workDir, String port,
                          I18nConfig.MessageSourceAccessorHolder messages) {
        this.currentVersion = currentVersion;
        this.feed = feed;
        this.downloader = downloader;
        this.launcher = launcher;
        this.shutdown = shutdown;
        this.jarLocation = jarLocation;
        this.workDir = workDir;
        this.port = port;
        this.messages = messages;
        restoreReadyFromMarker();
    }

    /**
     * Resolves a key immediately, in the current request's locale. For the exceptions that reach
     * the browser as-is: {@code app.js} renders a 409 body verbatim, so the text has to be
     * finished before it is thrown.
     *
     * <p>Only call this from a request thread. The download thread has no locale in scope, and
     * resolving there would bake in whichever one the JVM happened to start with — that is what
     * {@link Localized} is for.
     */
    private String text(String key, Object... args) {
        return messages.get(key, LocaleContextHolder.getLocale(), args);
    }

    // ── public flow ────────────────────────────────────────────────────────────────────

    /**
     * Starts an upgrade from the chosen mirror. Synchronous validation failures throw; a
     * failing feed call or download lands in {@link Phase#FAILED} for the poller to show.
     */
    public void start(String sourceName) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException(text("error.upgrade.busy"));
        }
        boolean handedOff = false;
        try {
            Source source;
            try {
                source = Source.valueOf(sourceName.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(text("error.upgrade.unknownSource", sourceName));
            }
            Path jar = requireJarFile();

            Release release;
            try {
                release = feed.latestFrom(source);
            } catch (Exception e) {
                log.warn("Upgrade: release lookup on {} failed", source, e);
                status = failed(new Localized("upgrade.lookupFailed.detail", describe(e)));
                return;
            }
            if (release == null) {
                status = failed(new Localized("upgrade.lookupFailed.empty", source.name()));
                return;
            }

            String target = VersionService.stripV(release.tag());
            String current = currentVersion.get();
            if (current != null && VersionService.compareVersions(target, current) <= 0) {
                throw new IllegalStateException(text("error.upgrade.alreadyLatest", current));
            }
            Asset asset = release.jarAsset(JAR_NAME);
            if (asset == null || !StringUtils.hasText(asset.url())) {
                throw new IllegalStateException(text("error.upgrade.noAsset", JAR_NAME));
            }

            status = new Status(Phase.DOWNLOADING, null, target, source.name(), 0,
                    asset.sizeBytes() == null ? 0 : asset.sizeBytes());
            Thread worker = new Thread(
                    () -> downloadAndInstall(jar, asset, target, release.tag(), source),
                    "jync-upgrade");
            worker.setDaemon(true);
            worker.start();
            handedOff = true; // the worker's finally block now owns the busy flag
        } finally {
            if (!handedOff) {
                busy.set(false);
            }
        }
    }

    public Status status() {
        return status;
    }

    /**
     * Launches the detached restart helper and schedules this JVM's exit. Only valid from
     * {@link Phase#READY} — before an upgrade is installed there is nothing to restart into.
     */
    public void restart() {
        Status s = status;
        if (s.phase() != Phase.READY) {
            throw new IllegalStateException(text("error.upgrade.notReady"));
        }
        try {
            Path jar = requireJarFile();
            Path backup = backupPath(jar);
            Path script = jar.getParent().resolve(isWindows() ? PS1_SCRIPT_NAME : SHELL_SCRIPT_NAME);
            RestartSpec spec = new RestartSpec(
                    ProcessHandle.current().pid(),
                    javaBinary(),
                    jar.toString(),
                    backup.toString(),
                    workDir.get().toString(),
                    parsePort(),
                    jar + ".failed-" + LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            String content = isWindows() ? buildPowerShellScript(spec) : buildShellScript(spec);
            Files.writeString(script, content, StandardCharsets.UTF_8);
            launcher.launch(script, workDir.get());
        } catch (Exception e) {
            throw new IllegalStateException(text("error.upgrade.restartFailed", describe(e)), e);
        }
        status = new Status(Phase.RESTARTING, new Localized("upgrade.restarting"),
                s.targetVersion(), s.source(), s.bytesRead(), s.bytesTotal());
        shutdown.exit(EXIT_DELAY_MS);
    }

    /**
     * Boot-time tidy-up. Always drops a stale {@code .part}; the backup, marker and helper
     * scripts are removed only when the marker's target version equals the running version —
     * proof the new jar really booted. After a failed boot or a script rollback the versions
     * differ and the backup must survive for the operator.
     */
    public void cleanupAfterBoot() {
        try {
            Path jar = jarLocation.get();
            if (jar == null) {
                return;
            }
            Path dir = jar.getParent();
            if (dir != null) {
                Files.deleteIfExists(dir.resolve(jar.getFileName() + PART_SUFFIX));
            }
            Properties marker = readMarker();
            if (marker == null || dir == null) {
                return;
            }
            String target = marker.getProperty("targetVersion");
            String running = currentVersion.get();
            if (target != null && running != null && target.equals(running)) {
                String backupName = marker.getProperty("backup");
                if (StringUtils.hasText(backupName)) {
                    Files.deleteIfExists(dir.resolve(backupName));
                }
                Files.deleteIfExists(dir.resolve(MARKER_NAME));
                Files.deleteIfExists(dir.resolve(SHELL_SCRIPT_NAME));
                Files.deleteIfExists(dir.resolve(PS1_SCRIPT_NAME));
                log.info("Upgrade marker matched running version {}; removed backup and marker",
                        running);
                if (status.phase() == Phase.READY) {
                    status = Status.idle();
                }
            }
        } catch (Exception e) {
            log.warn("Upgrade boot cleanup failed: {}", e.toString());
        }
    }

    // ── download / verify / swap ───────────────────────────────────────────────────────

    private void downloadAndInstall(Path jar, Asset asset, String target, String tag,
                                    Source source) {
        Path part = jar.resolveSibling(jar.getFileName() + PART_SUFFIX);
        try {
            Files.deleteIfExists(part);
            Long expected = asset.sizeBytes();
            downloader.download(asset.url(), part, expected,
                    read -> status = new Status(Phase.DOWNLOADING, null, target, source.name(),
                            read, status.bytesTotal()));

            long actual = Files.size(part);
            if (expected != null && expected > 0 && actual != expected) {
                throw new LocalizedFailure(new Localized("upgrade.failed.sizeMismatch",
                        String.valueOf(expected), String.valueOf(actual)), null);
            }
            verifyZipIntegrity(part);
            verifyChecksum(part, asset, tag, source);

            Path backup = backupPath(jar);
            Files.move(jar, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(part, jar);
            } catch (IOException moveBack) {
                // The .part rename failed: put the old jar back, we are still runnable.
                try {
                    Files.move(backup, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Nothing better to do; the operator still has whichever file survived.
                }
                throw moveBack;
            }

            writeMarker(jar.getParent(), target, source, backup.getFileName().toString());
            status = new Status(Phase.READY,
                    new Localized("upgrade.ready", target), target, source.name(),
                    actual, actual);
            log.info("Upgrade installed {} from {} ({} bytes); backup at {}", target, source,
                    actual, backup.getFileName());
        } catch (Exception e) {
            log.warn("Upgrade to {} failed", target, e);
            deleteQuietly(part);
            status = failed(failureText(e));
        } finally {
            busy.set(false);
        }
    }

    /** Opens the download as a zip and walks every entry header: cheap, catches truncation. */
    private static void verifyZipIntegrity(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                entries.nextElement();
            }
        } catch (IOException e) {
            throw new LocalizedFailure(new Localized("upgrade.failed.notAJar", e.getMessage()), e);
        }
    }

    /**
     * Compares the downloaded bytes against the release's published SHA-256. Neither the size
     * check nor the zip check can catch bytes that were swapped for a different, equally valid
     * jar — a compromised mirror or a hijacked CDN redirect produces exactly that.
     *
     * <p>A digest published by the chosen mirror always wins. Gitee's API reports no checksums,
     * so a Gitee download is cross-checked against the same tag on GitHub, which does. No digest
     * anywhere is not a mismatch: the upgrade proceeds with the computed hash logged, since
     * blocking every upgrade from a mirror that publishes none would break the feature. A digest
     * that disagrees fails closed — the {@code .part} is deleted and the running jar never moves.
     */
    private void verifyChecksum(Path part, Asset asset, String tag, Source source)
            throws IOException {
        String computed = sha256(part);
        log.info("Upgrade: downloaded {} ({}) from {}; SHA-256 {}", JAR_NAME, tag, source, computed);
        String expected = publishedSha256(asset, tag, source);
        if (expected == null) {
            log.warn("Upgrade: {} {} from {} publishes no SHA-256; the download could not be"
                    + " verified (computed {})", JAR_NAME, tag, source, computed);
            return;
        }
        if (!expected.equalsIgnoreCase(computed)) {
            throw new LocalizedFailure(
                    new Localized("upgrade.failed.checksumMismatch", expected, computed), null);
        }
    }

    /** The release's own digest, or the other mirror's for the same tag, or null. */
    private String publishedSha256(Asset asset, String tag, Source source) {
        String own = normalizeDigest(asset.sha256());
        if (own != null) {
            return own;
        }
        Source mirror = source == Source.GITHUB ? Source.GITEE : Source.GITHUB;
        try {
            Release release = feed.byTagFrom(mirror, tag);
            Asset counterpart = release == null ? null : release.jarAsset(JAR_NAME);
            String digest = counterpart == null ? null : normalizeDigest(counterpart.sha256());
            if (digest != null) {
                log.info("Upgrade: {} publishes no SHA-256 for {}; checking against {} instead",
                        source, tag, mirror);
            }
            return digest;
        } catch (Exception e) {
            // Best effort only: an unreachable or unknown mirror means "no digest", never a
            // failed verification — the upgrade still gets the warning path above.
            log.debug("Upgrade: cross-mirror digest lookup on {} for {} failed: {}", mirror, tag,
                    e.toString());
            return null;
        }
    }

    private static String normalizeDigest(String digest) {
        return StringUtils.hasText(digest) ? digest.trim() : null;
    }

    /** Streaming hex SHA-256; a jar is far too large to read into a byte array. */
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new LocalizedFailure(new Localized("upgrade.failed.noSha256"), e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private void writeMarker(Path dir, String target, Source source, String backupName)
            throws IOException {
        if (dir == null) {
            return;
        }
        Properties marker = new Properties();
        marker.setProperty("targetVersion", target);
        marker.setProperty("backup", backupName);
        marker.setProperty("source", source.name());
        marker.setProperty("installedAt", LocalDateTime.now().toString());
        try (OutputStream out = Files.newOutputStream(dir.resolve(MARKER_NAME))) {
            marker.store(out, "Written by the Jync one-click upgrade; safe to delete.");
        }
    }

    private Properties readMarker() {
        try {
            Path jar = jarLocation.get();
            if (jar == null || jar.getParent() == null) {
                return null;
            }
            Path marker = jar.getParent().resolve(MARKER_NAME);
            if (!Files.isRegularFile(marker)) {
                return null;
            }
            Properties props = new Properties();
            try (var in = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
                props.load(in);
            }
            return props;
        } catch (Exception e) {
            log.debug("Upgrade marker unreadable: {}", e.toString());
            return null;
        }
    }

    /** A marker whose target differs from the running version = an installed, pending upgrade. */
    private void restoreReadyFromMarker() {
        Properties marker = readMarker();
        if (marker == null) {
            return;
        }
        String target = marker.getProperty("targetVersion");
        String running = currentVersion.get();
        if (StringUtils.hasText(target) && !target.equals(running)) {
            status = new Status(Phase.READY,
                    new Localized("upgrade.ready", target), target,
                    marker.getProperty("source"), 0, 0);
        }
    }

    private Path requireJarFile() {
        Path jar = jarLocation.get();
        if (jar == null || !jar.getFileName().toString().toLowerCase().endsWith(".jar")
                || !Files.isRegularFile(jar)) {
            throw new IllegalStateException(text("error.upgrade.notAJar", jar));
        }
        return jar;
    }

    private static Path backupPath(Path jar) {
        return jar.resolveSibling(jar.getFileName() + BACKUP_SUFFIX);
    }

    private static Status failed(Localized message) {
        return new Status(Phase.FAILED, message, null, null, 0, 0);
    }

    /**
     * A failed check already says what went wrong; anything else is an exception from somewhere
     * else, so its own detail becomes the argument rather than the whole set of wording.
     */
    private static Localized failureText(Exception e) {
        return e instanceof LocalizedFailure f
                ? f.message()
                : new Localized("upgrade.failed.detail", describe(e));
    }

    private static String describe(Exception e) {
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private Integer parsePort() {
        try {
            return port == null ? null : Integer.valueOf(port.trim());
        } catch (NumberFormatException e) {
            return null; // e.g. server.port=0 or a placeholder: skip the readiness probe
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        return home + java.io.File.separator + "bin" + java.io.File.separator + "java"
                + (isWindows() ? ".exe" : "");
    }

    // ── generated restart helper scripts ───────────────────────────────────────────────

    /**
     * Bash helper: waits for the old JVM to disappear, relaunches the jar, probes the HTTP
     * port (when known) and rolls the backup over the jar if the new process dies or never
     * becomes ready. Written next to the jar, logged under {@code logs/jync-restart.log}.
     */
    public static String buildShellScript(RestartSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# Generated by the Jync one-click upgrade; safe to delete.\n");
        sb.append("OLD_PID=").append(spec.oldPid()).append('\n');
        sb.append("JAVA=").append(shq(spec.javaBinary())).append('\n');
        sb.append("JAR=").append(shq(spec.jarPath())).append('\n');
        sb.append("BACKUP=").append(shq(spec.backupPath())).append('\n');
        sb.append("FAILED=").append(shq(spec.failedPath())).append('\n');
        sb.append("WORKDIR=").append(shq(spec.workDir())).append('\n');
        sb.append("mkdir -p \"$WORKDIR/logs\"\n");
        sb.append("LOG=\"$WORKDIR/logs/jync-restart.log\"\n");
        sb.append("log() { echo \"$(date '+%F %T') $*\" >> \"$LOG\"; }\n");
        sb.append("rollback() {\n");
        sb.append("  log \"$1\"\n");
        sb.append("  mv \"$JAR\" \"$FAILED\" 2>/dev/null\n");
        sb.append("  mv \"$BACKUP\" \"$JAR\"\n");
        sb.append("  cd \"$WORKDIR\" && nohup \"$JAVA\" -jar \"$JAR\" >> \"$WORKDIR/logs/jync.log\" 2>&1 &\n");
        sb.append("  log \"rolled back to previous jar, pid $!\"\n");
        sb.append("}\n");
        sb.append("log \"waiting for old process $OLD_PID to exit\"\n");
        sb.append("for i in $(seq 1 60); do\n");
        sb.append("  if kill -0 \"$OLD_PID\" 2>/dev/null; then sleep 1; else break; fi\n");
        sb.append("done\n");
        sb.append("if kill -0 \"$OLD_PID\" 2>/dev/null; then\n");
        sb.append("  log \"old process still alive after 60s; aborting\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n");
        sb.append("cd \"$WORKDIR\" || exit 1\n");
        sb.append("log \"starting new process: $JAVA -jar $JAR\"\n");
        sb.append("nohup \"$JAVA\" -jar \"$JAR\" >> \"$WORKDIR/logs/jync.log\" 2>&1 &\n");
        sb.append("NEW_PID=$!\n");
        if (spec.port() != null) {
            sb.append("READY=0\n");
            sb.append("for i in $(seq 1 180); do\n");
            sb.append("  if ! kill -0 \"$NEW_PID\" 2>/dev/null; then break; fi\n");
            sb.append("  if (exec 3<>/dev/tcp/127.0.0.1/").append(spec.port())
                    .append(") 2>/dev/null; then READY=1; exec 3>&- 2>/dev/null; break; fi\n");
            sb.append("  sleep 1\n");
            sb.append("done\n");
            sb.append("if [ \"$READY\" = \"1\" ]; then\n");
            sb.append("  log \"new process is up (pid $NEW_PID)\"\n");
            sb.append("else\n");
            sb.append("  kill \"$NEW_PID\" 2>/dev/null\n");
            sb.append("  rollback \"new process never became ready on port ")
                    .append(spec.port()).append("\" || true\n");
            sb.append("fi\n");
        } else {
            sb.append("# No usable port configured: only guard against an early crash.\n");
            sb.append("sleep 15\n");
            sb.append("if kill -0 \"$NEW_PID\" 2>/dev/null; then\n");
            sb.append("  log \"new process alive (pid $NEW_PID)\"\n");
            sb.append("else\n");
            sb.append("  rollback \"new process died during startup\" || true\n");
            sb.append("fi\n");
        }
        return sb.toString();
    }

    /** Windows counterpart of {@link #buildShellScript(RestartSpec)}. */
    public static String buildPowerShellScript(RestartSpec spec) {
        String java = psq(spec.javaBinary());
        String jar = psq(spec.jarPath());
        String backup = psq(spec.backupPath());
        String failed = psq(spec.failedPath());
        String work = psq(spec.workDir());
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by the Jync one-click upgrade; safe to delete.\n");
        sb.append("$oldPid = ").append(spec.oldPid()).append('\n');
        sb.append("$java = ").append(java).append('\n');
        sb.append("$jar = ").append(jar).append('\n');
        sb.append("$backup = ").append(backup).append('\n');
        sb.append("$failed = ").append(failed).append('\n');
        sb.append("$workDir = ").append(work).append('\n');
        sb.append("$logDir = Join-Path $workDir 'logs'\n");
        sb.append("New-Item -ItemType Directory -Force -Path $logDir | Out-Null\n");
        sb.append("$log = Join-Path $logDir 'jync-restart.log'\n");
        sb.append("function Log($m) { Add-Content -Path $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $m) }\n");
        sb.append("function Rollback($m) {\n");
        sb.append("  Log $m\n");
        sb.append("  Move-Item $jar $failed -Force -ErrorAction SilentlyContinue\n");
        sb.append("  Move-Item $backup $jar -Force\n");
        sb.append("  Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory $workDir -WindowStyle Hidden\n");
        sb.append("  Log 'rolled back to previous jar'\n");
        sb.append("}\n");
        sb.append("Log \"waiting for old process $oldPid to exit\"\n");
        sb.append("$waited = 0\n");
        sb.append("while ($waited -lt 60 -and (Get-Process -Id ").append(spec.oldPid())
                .append(" -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1; $waited++ }\n");
        sb.append("if (Get-Process -Id ").append(spec.oldPid())
                .append(" -ErrorAction SilentlyContinue) { Log 'old process still alive; aborting'; exit 1 }\n");
        sb.append("Log 'starting new process'\n");
        sb.append("$proc = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -WorkingDirectory $workDir -PassThru -WindowStyle Hidden\n");
        if (spec.port() != null) {
            sb.append("$ready = $false\n");
            sb.append("for ($i = 0; $i -lt 180; $i++) {\n");
            sb.append("  if ($proc.HasExited) { break }\n");
            sb.append("  $client = New-Object System.Net.Sockets.TcpClient\n");
            sb.append("  try { $client.Connect('127.0.0.1', ").append(spec.port())
                    .append("); $ready = $true; break } catch { } finally { $client.Close() }\n");
            sb.append("  Start-Sleep -Seconds 1\n");
            sb.append("}\n");
            sb.append("if ($ready) { Log \"new process is up (pid $($proc.Id))\" }\n");
            sb.append("else {\n");
            sb.append("  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }\n");
            sb.append("  Rollback 'new process never became ready'\n");
            sb.append("}\n");
        } else {
            sb.append("# No usable port configured: only guard against an early crash.\n");
            sb.append("Start-Sleep -Seconds 15\n");
            sb.append("if (-not $proc.HasExited) { Log \"new process alive (pid $($proc.Id))\" }\n");
            sb.append("else { Rollback 'new process died during startup' }\n");
        }
        return sb.toString();
    }

    /** PowerShell single-quoted literal: only {@code '} needs escaping, by doubling. */
    private static String psq(String raw) {
        return "'" + raw.replace("'", "''") + "'";
    }

    /**
     * POSIX shell single-quoted literal. Single quotes cannot be escaped inside single
     * quotes, so {@code '} is closed, escaped and reopened: {@code '} → {@code '\''}.
     */
    private static String shq(String raw) {
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    // ── production defaults ────────────────────────────────────────────────────────────

    /** Streams the asset with progress; follows redirects (both forges redirect to a CDN). */
    static JarDownloader httpDownloader() {
        return (url, target, expectedBytes, progress) -> {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new LocalizedFailure(new Localized("upgrade.failed.http",
                        String.valueOf(response.statusCode())), null);
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                    progress.accept(total);
                }
            }
        };
    }

    static RestartLauncher processLauncher() {
        return (script, workDir) -> {
            ProcessBuilder builder = isWindows()
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                            "Bypass", "-File", script.toString())
                    : new ProcessBuilder("/usr/bin/env", "bash", script.toString());
            builder.directory(workDir.toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start(); // deliberately not waitFor(): the helper outlives this JVM
        };
    }

    /** Sleeps, then exits; JVM shutdown hooks give Spring its graceful shutdown. */
    static Shutdown delayedExit() {
        return delayMillis -> {
            Thread exitThread = new Thread(() -> {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }, "jync-upgrade-exit");
            exitThread.setDaemon(true);
            exitThread.start();
        };
    }

    /** Running jar (or classes dir when exploded); null when the location is unknowable. */
    static Path currentJarLocation() {
        try {
            var codeSource = UpgradeService.class.getProtectionDomain().getCodeSource();
            return codeSource == null ? null : jarPathFromLocation(codeSource.getLocation());
        } catch (Exception e) {
            log.debug("Cannot resolve jar location: {}", e.toString());
            return null;
        }
    }

    /**
     * Turns a class's code-source location into the file it is running from. Launched with
     * {@code java -jar}, Spring Boot defines application classes under a nested URL such as
     * {@code jar:file:/app/jync.jar!/BOOT-INF/classes!/}; {@link Path#of(URI)} cannot open a
     * {@code jar:} URI, so unwrap it to the outer jar — the file an upgrade replaces. An
     * exploded run (IDE, {@code target/classes}) comes back as that directory, and the caller
     * rejects it: there is no jar to replace.
     */
    static Path jarPathFromLocation(URL location) throws URISyntaxException {
        if (location == null) {
            return null;
        }
        if (!"jar".equalsIgnoreCase(location.getProtocol())) {
            return Path.of(location.toURI());
        }
        String spec = location.toString();
        int separator = spec.indexOf("!/");
        if (separator < 0) {
            return null;
        }
        return Path.of(URI.create(spec.substring("jar:".length(), separator)));
    }

    /**
     * The Spring-wired instance. Declared as a nested {@code @Component} so the outer class
     * stays a plain, seam-injected object the tests construct directly.
     */
    @Component
    public static class Bean extends UpgradeService {

        @Autowired
        public Bean(ObjectProvider<BuildProperties> buildProperties, SourceAwareFeed feed,
                    @Value("${server.port:8080}") String port,
                    I18nConfig.MessageSourceAccessorHolder messages) {
            super(() -> {
                        BuildProperties props = buildProperties.getIfAvailable();
                        String version = props == null ? null : props.getVersion();
                        return StringUtils.hasText(version) ? version.trim() : null;
                    },
                    feed,
                    httpDownloader(),
                    processLauncher(),
                    delayedExit(),
                    UpgradeService::currentJarLocation,
                    () -> Path.of(System.getProperty("user.dir")),
                    port,
                    messages);
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            cleanupAfterBoot();
        }
    }
}
