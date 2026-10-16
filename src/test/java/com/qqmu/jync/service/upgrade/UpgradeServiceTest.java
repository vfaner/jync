package com.qqmu.jync.service.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.upgrade.UpgradeService.RestartSpec;
import com.qqmu.jync.service.upgrade.UpgradeService.Status;
import com.qqmu.jync.service.version.ReleaseFeed.Asset;
import com.qqmu.jync.service.version.ReleaseFeed.Release;
import com.qqmu.jync.service.version.ReleaseFeed.Source;

/**
 * The one-click upgrade: download the fixed-name jar asset, verify bytes/zip identity,
 * swap with a rename dance the running jar survives (Windows allows renaming an open jar),
 * and only restart afterwards. The running jar location, the downloader, the process launch
 * and the exit call are all injected seams — no socket, process or System.exit in here.
 */
class UpgradeServiceTest {

    private static final String JAR_ENTRY = "BOOT-INF/classes/com/qqmu/jync/JyncApplication.class";

    @TempDir
    Path tempDir;

    Path jarFile;
    Path workDir;
    FakeFeed feed;
    RecordingLauncher launcher;
    RecordingShutdown shutdown;

    /** A downloader that copies a prepared blob (and can hang / write bad bytes). */
    final class ScriptedDownloader implements UpgradeService.JarDownloader {
        volatile byte[] blob;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile RuntimeException failure;
        final AtomicLong lastReported = new AtomicLong();

        @Override
        public void download(String url, Path target, Long expectedBytes,
                             java.util.function.LongConsumer progress) throws Exception {
            if (entered != null) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            if (failure != null) {
                throw failure;
            }
            Files.write(target, blob);
            progress.accept(blob.length);
            lastReported.set(blob.length);
        }
    }

    final class RecordingLauncher implements UpgradeService.RestartLauncher {
        Path script;
        Path launchedFrom;

        @Override
        public void launch(Path script, Path workDir) {
            this.script = script;
            this.launchedFrom = workDir;
        }
    }

    static final class RecordingShutdown implements UpgradeService.Shutdown {
        Long requestedAfterMillis;

        @Override
        public void exit(long delayMillis) {
            requestedAfterMillis = delayMillis;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jarFile = tempDir.resolve("app").resolve("jync.jar");
        Files.createDirectories(jarFile.getParent());
        Files.write(jarFile, makeFatJar("old-jar-bytes"));
        workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        feed = new FakeFeed();
        launcher = new RecordingLauncher();
        shutdown = new RecordingShutdown();
    }

    private UpgradeService service(String currentVersion, ScriptedDownloader downloader) {
        return new UpgradeService(
                () -> currentVersion,
                feed,
                downloader,
                launcher,
                shutdown,
                () -> jarFile,
                () -> workDir,
                "8081");
    }

    private static byte[] makeFatJar(String marker) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(JAR_ENTRY));
            zos.write(marker.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private void awaitPhase(UpgradeService svc, UpgradeService.Phase phase) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (svc.status().phase() == phase) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("never reached " + phase + ", was " + svc.status().phase()
                + " (" + svc.status().message() + ")");
    }

    @Test
    void happyPathDownloadsVerifiesSwapsAndKeepsBackupAndMarker() throws Exception {
        byte[] nextJar = makeFatJar("new-jar-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.READY);

        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
        assertThat(Files.readAllBytes(tempDir.resolve("app/jync.jar.bak")))
                .startsWith(new byte[]{'P', 'K'}); // old fat jar backed up, not deleted
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();

        Status status = svc.status();
        assertThat(status.targetVersion()).isEqualTo("1.8.0");
        assertThat(status.source()).isEqualTo("GITEE");
        assertThat(status.bytesTotal()).isEqualTo(nextJar.length);
        assertThat(downloader.lastReported).hasValue(nextJar.length);

        Properties marker = new Properties();
        try (var in = Files.newBufferedReader(
                tempDir.resolve("app/.jync-update.properties"), StandardCharsets.UTF_8)) {
            marker.load(in);
        }
        assertThat(marker.getProperty("targetVersion")).isEqualTo("1.8.0");
        assertThat(marker.getProperty("backup")).isEqualTo("jync.jar.bak");
    }

    @Test
    void declaredSizeMismatchFailsWithoutTouchingTheRunningJar() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        // GitHub tells the true size; lie about it to simulate a truncated transfer.
        feed.release = release(Source.GITHUB, "v1.8.0", (long) nextJar.length + 500);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITHUB");
        awaitPhase(svc, UpgradeService.Phase.FAILED);

        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
        assertThat(tempDir.resolve("app/jync.jar.bak")).doesNotExist();
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        assertThat(svc.status().message()).contains("字节");
    }

    @Test
    void garbageBytesWithoutDeclaredSizeStillFailZipVerification() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = "this is not a jar".getBytes(StandardCharsets.UTF_8);
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);

        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
        assertThat(svc.status().message()).contains("zip");
    }

    @Test
    void downloadErrorFailsAndLeavesRunningJarAlone() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.failure = new RuntimeException("socket reset");
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);
        assertThat(svc.status().message()).contains("socket reset");
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
    }

    @Test
    void refusesWhenRunningFromAnExplodedClassTree() {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = new UpgradeService(
                () -> "1.7.2", feed, downloader, launcher, shutdown,
                () -> tempDir.resolve("classes"), () -> workDir, "8081");

        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jar");
    }

    @Test
    void refusesWhenAlreadyUpToDate() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = release(Source.GITEE, "v1.7.2", null);
        UpgradeService svc = service("1.7.2", downloader);

        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesWhenReleaseHasNoJarAsset() {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = new Release("v1.8.0", "https://gitee.com/o/r/releases/tag/v1.8.0",
                null, "body", List.of(), Source.GITEE);
        UpgradeService svc = service("1.7.2", downloader);

        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jync.jar");
    }

    @Test
    void aSecondStartWhileDownloadingIsRejected() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = makeFatJar("new");
        downloader.entered = new CountDownLatch(1);
        downloader.release = new CountDownLatch(1);
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        assertThat(downloader.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进行中");
        downloader.release.countDown();
        awaitPhase(svc, UpgradeService.Phase.READY);
    }

    @Test
    void explicitSourceFailureIsReportedNotRerouted() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.failure = new RuntimeException("gitee blocked");
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);
        assertThat(svc.status().message()).contains("gitee blocked");
    }

    @Test
    void readyStatusIsRebuiltFromMarkerAcrossInstances() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        runToReady(service("1.7.2", downloader));

        // Simulate a page reload / fresh bean reading the persisted marker.
        UpgradeService rebuilt = service("1.7.2", new ScriptedDownloader());
        assertThat(rebuilt.status().phase()).isEqualTo(UpgradeService.Phase.READY);
        assertThat(rebuilt.status().targetVersion()).isEqualTo("1.8.0");
    }

    private void runToReady(UpgradeService svc) {
        try {
            svc.start("GITEE");
            awaitPhase(svc, UpgradeService.Phase.READY);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void bootCleanupRemovesBackupAndMarkerOnceTheNewVersionIsRunning() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        Path backup = tempDir.resolve("app/jync.jar.bak");
        Path marker = tempDir.resolve("app/.jync-update.properties");
        runToReady(service("1.7.2", downloader));
        assertThat(backup).exists();

        // New process boots ON the new jar: marker matches running version → tidy up.
        service("1.8.0", new ScriptedDownloader()).cleanupAfterBoot();

        assertThat(backup).doesNotExist();
        assertThat(marker).doesNotExist();
    }

    @Test
    void bootCleanupKeepsTheBackupWhenVersionsDoNotMatch() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        Path backup = tempDir.resolve("app/jync.jar.bak");
        runToReady(service("1.7.2", downloader));

        // Booted on something other than the installed target (manual rollback/downgrade):
        // the backup stays so the operator can still recover it.
        service("1.7.2", new ScriptedDownloader()).cleanupAfterBoot();

        assertThat(backup).exists();
    }

    @Test
    void restartWritesTheHelperScriptLaunchesItAndRequestsExit() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);
        runToReady(svc);

        svc.restart();

        assertThat(svc.status().phase()).isEqualTo(UpgradeService.Phase.RESTARTING);
        assertThat(launcher.script).exists();
        assertThat(launcher.launchedFrom).isEqualTo(workDir);
        String script = Files.readString(launcher.script);
        assertThat(script).contains(jarFile.toString());
        assertThat(script).contains(workDir.toString());
        assertThat(script).contains("8081");
        assertThat(script).contains("jync.jar.bak");
        assertThat(shutdown.requestedAfterMillis).isNotNull().isPositive();
    }

    @Test
    void restartIsRejectedBeforeAnUpgradeIsReady() {
        UpgradeService svc = service("1.7.2", new ScriptedDownloader());
        assertThatThrownBy(svc::restart).isInstanceOf(IllegalStateException.class);
    }

    // ── script content (pure builders, platform-specific) ─────────────────────────────

    @Test
    void shellScriptWaitsForOldProcessGuardsReadinessAndRollsBack() {
        RestartSpec spec = new RestartSpec(12345L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", 8081,
                "/opt/jync/jync.jar.failed-20261015");
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).contains("#!/usr/bin/env bash");
        assertThat(script).contains("12345");
        assertThat(script).contains("kill -0 \"$OLD_PID\"");           // wait for old JVM
        assertThat(script).contains("/dev/tcp/127.0.0.1/8081");       // readiness probe
        assertThat(script).contains("mv \"$BACKUP\" \"$JAR\"");       // rollback restore
        assertThat(script).contains("logs/jync-restart.log");
    }

    @Test
    void shellScriptWithoutAUsablePortSkipsTheReadinessProbe() {
        RestartSpec spec = new RestartSpec(12345L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", null,
                "/opt/jync/jync.jar.failed-x");
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).doesNotContain("/dev/tcp");
        assertThat(script).contains("kill -0 \"$NEW_PID\""); // still roll back on early death
    }

    @Test
    void powershellScriptWaitsLaunchesAndRollsBack() {
        RestartSpec spec = new RestartSpec(999L, "C:\\Program Files\\Java\\bin\\java.exe",
                "C:\\app\\jync.jar", "C:\\app\\jync.jar.bak", "C:\\app", 8080,
                "C:\\app\\jync.jar.failed-x");
        String script = UpgradeService.buildPowerShellScript(spec);

        assertThat(script).contains("Get-Process -Id 999");
        assertThat(script).contains("'C:\\Program Files\\Java\\bin\\java.exe'");
        assertThat(script).contains("'C:\\app\\jync.jar'");
        assertThat(script).contains("System.Net.Sockets.TcpClient");
        assertThat(script).contains("127.0.0.1", "8080");
        assertThat(script).contains("Move-Item $backup $jar -Force");
    }

    @Test
    void shellScriptEscapesSingleQuotesInPaths() {
        // A single quote inside a path must not break out of the generated script's quoting.
        RestartSpec spec = new RestartSpec(1L, "/opt/jy'bin/java",
                "/opt/jy'nc/jync.jar", "/opt/jy'nc/jync.jar.bak", "/opt/jy'nc", 8081,
                "/opt/jy'nc/jync.jar.failed-x");
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).contains("JAVA='/opt/jy'\\''bin/java'");
        assertThat(script).contains("JAR='/opt/jy'\\''nc/jync.jar'");
        assertThat(script).doesNotContain("'/opt/jy'nc/jync.jar'");
    }

    @Test
    void powershellEscapesSingleQuotesInPaths() {
        RestartSpec spec = new RestartSpec(1L, "C:\\app'o\\java.exe",
                "C:\\app'o\\jync.jar", "C:\\app'o\\jync.jar.bak", "C:\\work", 8080,
                "C:\\app'o\\jync.jar.failed-x");
        String script = UpgradeService.buildPowerShellScript(spec);

        assertThat(script).contains("'C:\\app''o\\java.exe'");
    }

    /** Minimal controllable feed: one canned release or a failure, and latestFrom only. */
    private static final class FakeFeed implements com.qqmu.jync.service.version.SourceAwareFeed {
        Release release;
        Exception failure;

        @Override
        public Release latestFrom(Source source) throws Exception {
            if (failure != null) {
                throw failure;
            }
            return release;
        }
    }

    private static Release release(Source source, String tag, Long size) {
        return new Release(tag,
                "https://example.invalid/" + source + "/" + tag,
                null, "body",
                List.of(new Asset("jync.jar",
                        "https://example.invalid/" + source + "/" + tag + "/jync.jar", size)),
                source);
    }

    // Status snapshot sanity lives here too so the record's shape is pinned at the source.
    @Test
    void statusExposesProgressFieldsForPolling() {
        Status idle = Status.idle();
        assertThat(idle.phase()).isEqualTo(UpgradeService.Phase.IDLE);
        assertThat(idle.bytesRead()).isZero();
        assertThat(idle.bytesTotal()).isZero();
    }

    @Test
    void stalePartialDownloadIsRemovedOnBoot() throws Exception {
        Path part = tempDir.resolve("app/jync.jar.part");
        try (OutputStream out = Files.newOutputStream(part)) {
            out.write(new byte[10]);
        }
        service("1.7.2", new ScriptedDownloader()).cleanupAfterBoot();
        assertThat(part).doesNotExist();
    }

    @Test
    void bootCleanupAlsoRemovesOldRestartScripts() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService ready = service("1.8.0", downloader);
        // marker from a previous install exists; scripts were left beside the jar
        Files.writeString(tempDir.resolve("app/jync-update-restart.sh"), "# old");
        Files.writeString(tempDir.resolve("app/jync-update-restart.ps1"), "# old");
        // emulate successful prior install marker directly
        Properties p = new Properties();
        p.setProperty("targetVersion", "1.8.0");
        p.setProperty("backup", "jync.jar.bak");
        try (OutputStream os = Files.newOutputStream(tempDir.resolve("app/.jync-update.properties"))) {
            p.store(os, null);
        }
        Files.write(tempDir.resolve("app/jync.jar.bak"), makeFatJar("old"));

        ready.cleanupAfterBoot();

        assertThat(tempDir.resolve("app/jync-update-restart.sh")).doesNotExist();
        assertThat(tempDir.resolve("app/jync-update-restart.ps1")).doesNotExist();
    }
}
