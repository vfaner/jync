package com.qqmu.jync.service.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.upgrade.UpgradeService.RestartSpec;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.qqmu.jync.config.I18nConfig;
import com.qqmu.jync.service.upgrade.UpgradeService.Localized;
import com.qqmu.jync.service.upgrade.UpgradeService.Status;
import com.qqmu.jync.service.version.ArtifactComparison;
import com.qqmu.jync.service.version.ReleaseFeed;
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
        /** What the server declares as Content-Length; 0 = the response declares none. */
        volatile long declaredTotal;
        final AtomicLong lastReported = new AtomicLong();

        @Override
        public void download(String url, Path target, Long expectedBytes,
                             UpgradeService.DownloadProgress progress) throws Exception {
            if (failure != null) {
                throw failure;
            }
            Files.write(target, blob);
            // Half the blob first, so a test can hold the download open and read the status.
            progress.update(blob.length / 2, declaredTotal);
            if (entered != null) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            progress.update(blob.length, declaredTotal);
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
        return service(currentVersion, downloader, "8081");
    }

    private UpgradeService service(String currentVersion, ScriptedDownloader downloader,
                                   String port) {
        return service(currentVersion, downloader, port, null);
    }

    private UpgradeService service(String currentVersion, ScriptedDownloader downloader,
                                   String port, ArtifactComparison comparison) {
        return new UpgradeService(
                () -> currentVersion,
                feed,
                downloader,
                launcher,
                shutdown,
                () -> jarFile,
                () -> workDir,
                port,
                messages(),
                comparison);
    }

    /** A service that believes it was started with these launch arguments. */
    private UpgradeService serviceWithArgs(String currentVersion, ScriptedDownloader downloader,
                                           List<String> jvmArgs, List<String> programArgs) {
        return new UpgradeService(
                () -> currentVersion,
                feed,
                downloader,
                launcher,
                shutdown,
                () -> jarFile,
                () -> workDir,
                "8081",
                messages(),
                null,
                () -> jvmArgs,
                () -> programArgs);
    }

    /**
     * The real bundles, mirroring application.yml. Status messages are collected unresolved and
     * asserted by key, so the assertions in this class do not depend on a locale — but the
     * exception paths resolve at throw time and do, so the holder is the shipped one.
     */
    private static I18nConfig.MessageSourceAccessorHolder messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new I18nConfig.MessageSourceAccessorHolder(source);
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
    void downloadProgressCarriesTheServerDeclaredTotalWhenTheReleaseHasNoSize() throws Exception {
        // Gitee's API reports no asset size, so the progress bar used to sit at 0 for the whole
        // download. The HTTP response's Content-Length is a total the downloader can report.
        byte[] nextJar = makeFatJar("new-jar-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        downloader.declaredTotal = 4096L;
        downloader.entered = new CountDownLatch(1);
        downloader.release = new CountDownLatch(1);
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        assertThat(downloader.entered.await(5, TimeUnit.SECONDS)).isTrue();

        Status status = svc.status();
        assertThat(status.phase()).isEqualTo(UpgradeService.Phase.DOWNLOADING);
        assertThat(status.bytesRead()).isEqualTo(nextJar.length / 2);
        assertThat(status.bytesTotal()).isEqualTo(4096L);

        downloader.release.countDown();
        awaitPhase(svc, UpgradeService.Phase.READY);
    }

    @Test
    void httpDownloaderFallsBackToContentLengthForTheTotal() throws Exception {
        byte[] payload = new byte[200_000];
        new java.util.Random(7).nextBytes(payload);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jync.jar", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        try {
            Path target = tempDir.resolve("downloaded.jar");
            List<long[]> reports = new java.util.ArrayList<>();
            UpgradeService.httpDownloader().download(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jync.jar",
                    target, null, (read, total) -> reports.add(new long[]{read, total}));

            assertThat(Files.readAllBytes(target)).isEqualTo(payload);
            assertThat(reports).isNotEmpty();
            long[] last = reports.get(reports.size() - 1);
            assertThat(last[0]).isEqualTo(payload.length);
            // expectedBytes is null (Gitee): the response's own Content-Length is the total.
            assertThat(last[1]).isEqualTo(payload.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void declaredSizeMismatchFailsWithoutTouchingTheRunningJar() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        // GitHub tells the true size; lie about it to simulate a truncated transfer. The lie is
        // over a thousand bytes so that a value rendered as a number would come back grouped
        // ("5,223") and the assertion below can catch it.
        long declared = (long) nextJar.length + 5000;
        feed.release = release(Source.GITHUB, "v1.8.0", declared);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITHUB");
        awaitPhase(svc, UpgradeService.Phase.FAILED);

        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
        assertThat(tempDir.resolve("app/jync.jar.bak")).doesNotExist();
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        // The wording is chosen by the reader's locale at poll time, so what the service owes
        // the poller is the key and the numbers, not a sentence.
        Localized message = svc.status().message();
        assertThat(message.key()).isEqualTo("upgrade.failed.sizeMismatch");
        assertThat(message.args()).containsExactly(
                String.valueOf(declared), String.valueOf(nextJar.length));
        // Rendered through the shipped bundle rather than asserted as a key alone: a byte count
        // passed as a number comes back as "5,223", which is not what this message said before
        // it became one.
        assertThat(messages().get(message.key(), Locale.ENGLISH, message.args()))
                .contains("expected " + declared + " bytes, got " + nextJar.length)
                .doesNotContain("5,223");
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
        assertThat(svc.status().message().key()).isEqualTo("upgrade.failed.notAJar");
    }

    @Test
    void downloadErrorFailsAndLeavesRunningJarAlone() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.failure = new RuntimeException("socket reset");
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);
        Localized message = svc.status().message();
        assertThat(message.key()).isEqualTo("upgrade.failed.detail");
        assertThat(message.args()).containsExactly("socket reset");
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
    }

    @Test
    void refusesWhenRunningFromAnExplodedClassTree() {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = new UpgradeService(
                () -> "1.7.2", feed, downloader, launcher, shutdown,
                () -> tempDir.resolve("classes"), () -> workDir, "8081", messages(), null);

        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jar");
    }

    /**
     * The reason the artifact comparison exists: v1.8.0 was re-uploaded under its own tag, so
     * "already on 1.8.0" is not "nothing to install". Provable difference -> the upgrade runs;
     * unprovable -> the old refusal stands, because reinstalling identical bytes is noise.
     */
    @Test
    void sameVersionWithARePublishedArtifactCanBeInstalled() throws Exception {
        byte[] nextJar = makeFatJar("re-published-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITHUB, "v1.8.0", (long) nextJar.length, sha256Of(nextJar));
        UpgradeService svc = service("1.8.0", downloader, "8081",
                release -> ArtifactComparison.Result.DIFFERENT);

        svc.start("GITHUB");
        awaitPhase(svc, UpgradeService.Phase.READY);

        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
    }

    @Test
    void sameVersionIsRefusedWhenTheArtifactComparisonSaysSame() {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = release(Source.GITHUB, "v1.8.0", null);
        UpgradeService svc = service("1.8.0", downloader, "8081",
                release -> ArtifactComparison.Result.SAME);

        assertThatThrownBy(() -> svc.start("GITHUB"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(downloader.blob).isNull();
    }

    @Test
    void sameVersionIsRefusedWhenTheArtifactComparisonCannotTell() {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.release = release(Source.GITHUB, "v1.8.0", null);
        UpgradeService svc = service("1.8.0", downloader, "8081",
                release -> ArtifactComparison.Result.UNKNOWN);

        assertThatThrownBy(() -> svc.start("GITHUB"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(downloader.blob).isNull();
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
        // Exceptions are already finished sentences by the time they are thrown -- the 409 body
        // carries them verbatim -- so they are rendered in the request locale. Pin it rather than
        // depending on whatever the JVM default happens to be.
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            assertThatThrownBy(() -> svc.start("GITEE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("进行中")
                    .hasMessageContaining("等待其完成");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
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
        Localized message = svc.status().message();
        assertThat(message.key()).isEqualTo("upgrade.lookupFailed.detail");
        assertThat(message.args()[0].toString()).contains("gitee blocked");
    }

    /**
     * A mirror refusing anonymous calls (HTTP 403/429) is its rate limit, not a broken release.
     * "Gitee API responded 403" tells an operator nothing they can act on; the dialog must say
     * which mirror did it and that switching sources is the way out.
     */
    @Test
    void aRateLimitedMirrorGetsAnActionableReasonInsteadOfAStatusNumber() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        feed.failure = new ReleaseFeed.RateLimitedException(Source.GITEE, 403);
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);
        Localized message = svc.status().message();
        assertThat(message.key()).isEqualTo("upgrade.lookupFailed.rateLimited");
        assertThat(message.args()).containsExactly("Gitee", 403);
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

    @Test
    void aMarkerWriteFailureAfterTheSwapStillLeavesTheUpgradeReady() throws Exception {
        byte[] oldJar = makeFatJar("old-jar-bytes");
        byte[] nextJar = makeFatJar("new-jar-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        // A directory where the marker file belongs makes the marker write fail after the
        // jar has already been swapped in — the one failure that lands PAST the point of no
        // return. Reporting it as FAILED offers the user a retry that would move the freshly
        // installed jar over the backup, destroying the only copy of the version still running.
        Files.createDirectory(tempDir.resolve("app/.jync-update.properties"));
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.READY);

        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
        assertThat(Files.readAllBytes(tempDir.resolve("app/jync.jar.bak"))).isEqualTo(oldJar);
        assertThat(svc.status().targetVersion()).isEqualTo("1.8.0");
    }

    @Test
    void startingAnotherUpgradeWhileOneAwaitsRestartIsRejected() throws Exception {
        byte[] oldJar = makeFatJar("old-jar-bytes");
        byte[] nextJar = makeFatJar("new-jar-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader);
        runToReady(svc);

        // Still running the old jar from memory: a second swap would BACK UP the already
        // installed jar and leave the restart script rolling back to a version that never ran.
        downloader.blob = makeFatJar("second-jar-bytes");
        feed.release = release(Source.GITEE, "v1.9.0", null);

        assertThatThrownBy(() -> svc.start("GITEE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(tempDir.resolve("app/jync.jar.bak"))).isEqualTo(oldJar);
        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
        assertThat(svc.status().phase()).isEqualTo(UpgradeService.Phase.READY);
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

    /**
     * After the helper script rolls back, it moves {@code .bak} over the jar, so the backup is
     * gone while the marker survives. The old version then boots, sees a marker whose target
     * differs, and used to report READY: the settings page kept the "restart to finish upgrade"
     * badge forever, and restarting launched the old jar again while the browser announced a
     * success that never happened. No backup means nothing is pending — the marker is stale.
     */
    @Test
    void aRolledBackUpgradeDoesNotLeaveAStaleReadyState() throws Exception {
        Properties p = new Properties();
        p.setProperty("targetVersion", "1.8.0");
        p.setProperty("backup", "jync.jar.bak");
        try (OutputStream os = Files.newOutputStream(
                tempDir.resolve("app/.jync-update.properties"))) {
            p.store(os, null);
        }
        // No jync.jar.bak: the rollback consumed it restoring the old jar.

        UpgradeService svc = service("1.7.2", new ScriptedDownloader());
        assertThat(svc.status().phase()).isEqualTo(UpgradeService.Phase.IDLE);

        svc.cleanupAfterBoot();
        assertThat(tempDir.resolve("app/.jync-update.properties")).doesNotExist();
    }

    /** {@code server.port=0} means "any free port"; probing port 0 can never succeed. */
    @Test
    void aZeroPortSkipsTheReadinessProbe() throws Exception {
        byte[] nextJar = makeFatJar("new");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = service("1.7.2", downloader, "0");
        runToReady(svc);

        svc.restart();

        assertThat(Files.readString(launcher.script)).doesNotContain("/dev/tcp");
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
    void powerShellScriptBytesStartWithAUtf8Bom() {
        // Windows PowerShell 5.1 decodes a BOM-less .ps1 in the system ANSI code page, so a
        // non-ASCII install path would be mojibake inside the script: the relaunch and the
        // rollback would both name a path that does not exist.
        String content = "# 升级脚本\n$jar = 'D:\\百目\\jync.jar'\n";
        byte[] bytes = UpgradeService.scriptBytes(content, true);

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    void shellScriptBytesCarryNoBom() {
        // A BOM ahead of "#!/usr/bin/env bash" makes the kernel reject the script.
        String content = "#!/usr/bin/env bash\necho ok\n";
        byte[] bytes = UpgradeService.scriptBytes(content, false);

        assertThat(bytes[0]).isEqualTo((byte) '#');
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(content);
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

    // ── launch arguments survive the restart ──────────────────────────────────────────

    /**
     * The restart helper used to relaunch with a bare {@code java -jar}, so every JVM flag
     * ({@code -Xmx}, certificates, system properties) and every program argument
     * ({@code --spring.profiles.active}) silently disappeared on the first one-click upgrade.
     * Those settings can decide which database or port the process binds, so the new process
     * came up with defaults its operator never chose.
     */
    @Test
    void shellScriptReplaysTheOriginalJvmAndProgramArguments() {
        RestartSpec spec = new RestartSpec(12345L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", 8081,
                "/opt/jync/jync.jar.failed-x",
                List.of("-Xmx1g", "-Dapp.mode=prod"),
                List.of("--spring.profiles.active=prod"));
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).contains("JVM_ARGS=('-Xmx1g' '-Dapp.mode=prod')");
        assertThat(script).contains("PROG_ARGS=('--spring.profiles.active=prod')");
        // Both the forward launch and the rollback relaunch must carry them.
        assertThat(script.lines()
                .filter(line -> line.contains("nohup \"$JAVA\""))
                .count()).isEqualTo(2);
        assertThat(script.lines()
                .filter(line -> line.contains("nohup \"$JAVA\""))
                .filter(line -> line.contains("\"${JVM_ARGS[@]}\"")
                        && line.contains("\"${PROG_ARGS[@]}\""))
                .count()).isEqualTo(2);
    }

    /**
     * Most installations start from an IDE or a plain {@code java -jar}: the runtime bean
     * reports no explicit options, and the generated script must stay exactly what it was
     * before this feature rather than gain empty arrays and quoting.
     */
    @Test
    void shellScriptIsUnchangedWhenTheJvmHadNoExplicitArguments() {
        RestartSpec spec = new RestartSpec(12345L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", 8081,
                "/opt/jync/jync.jar.failed-x");
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).doesNotContain("JVM_ARGS");
        assertThat(script).doesNotContain("PROG_ARGS");
        assertThat(script).contains(
                "nohup \"$JAVA\" -jar \"$JAR\" >> \"$WORKDIR/logs/jync.log\" 2>&1 &");
    }

    @Test
    void shellScriptQuotesArgumentsContainingQuotesAndSpaces() {
        RestartSpec spec = new RestartSpec(1L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", null,
                "/opt/jync/jync.jar.failed-x",
                List.of("-Dmsg=it's here"),
                List.of());
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).contains("JVM_ARGS=('-Dmsg=it'\\''s here')");
    }

    /** A debug agent belongs to the IDE session that already ended with the old JVM. */
    @Test
    void debugAgentArgumentsAreNotReplayed() {
        assertThat(UpgradeService.launchableJvmArgs(List.of(
                "-Xmx1g",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
                "-Xrunjdwp:transport=dt_socket,address=5005",
                "-Xdebug",
                "-Dfoo=bar")))
                .containsExactly("-Xmx1g", "-Dfoo=bar");
        assertThat(UpgradeService.launchableJvmArgs(null)).isEmpty();
    }

    @Test
    void restartCapturesTheRunningJvmAndProgramArguments() throws Exception {
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = makeFatJar("new");
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = serviceWithArgs("1.7.2", downloader,
                List.of("-Xmx1g", "-agentlib:jdwp=transport=dt_socket,address=5005"),
                List.of("--spring.profiles.active=prod"));

        runToReady(svc);
        svc.restart();

        String script = Files.readString(launcher.script);
        assertThat(script).contains("JVM_ARGS=('-Xmx1g')");
        assertThat(script).doesNotContain("jdwp");
        assertThat(script).contains("PROG_ARGS=('--spring.profiles.active=prod')");
    }

    /**
     * A {@code -D} value can be a credential (a database password, an OA secret). It has to
     * stay in the launch command or the restarted process cannot start, but it must never be
     * echoed into the restart log, which operators paste into tickets.
     */
    @Test
    void aSecretSystemPropertyIsNeverWrittenToTheRestartLog() {
        RestartSpec spec = new RestartSpec(1L, "/usr/bin/java",
                "/opt/jync/jync.jar", "/opt/jync/jync.jar.bak", "/opt/jync", 8081,
                "/opt/jync/jync.jar.failed-x",
                List.of("-Dspring.datasource.password=hunter2"),
                List.of());
        String script = UpgradeService.buildShellScript(spec);

        assertThat(script).contains("JVM_ARGS=('-Dspring.datasource.password=hunter2')");
        assertThat(script.lines()
                .filter(line -> line.contains("log ") || line.contains("Log "))
                .noneMatch(line -> line.contains("hunter2")))
                .isTrue();
    }

    @Test
    void powershellReplaysTheArgumentsAsOneQuotedArgumentList() {
        RestartSpec spec = new RestartSpec(9L, "C:\\Java\\bin\\java.exe",
                "C:\\app\\jync.jar", "C:\\app\\jync.jar.bak", "C:\\app", 8080,
                "C:\\app\\jync.jar.failed-x",
                List.of("-Xmx1g", "-Dname=a b"),
                List.of("--spring.profiles.active=prod"));
        String script = UpgradeService.buildPowerShellScript(spec);

        // One string, not an array: Start-Process joins array elements with spaces and does
        // not quote them, which is also why a jar path with spaces used to break the launch.
        assertThat(script).contains(
                "-ArgumentList '-Xmx1g \"-Dname=a b\" -jar C:\\app\\jync.jar"
                        + " --spring.profiles.active=prod'");
        assertThat(script.lines()
                .filter(line -> line.contains("-ArgumentList"))
                .count()).isEqualTo(2);
    }

    @Test
    void powershellQuotesAJarPathThatContainsSpaces() {
        RestartSpec spec = new RestartSpec(9L, "C:\\Program Files\\Java\\bin\\java.exe",
                "C:\\Program Files\\Jync\\jync.jar", "C:\\Program Files\\Jync\\jync.jar.bak",
                "C:\\Program Files\\Jync", 8080, "C:\\Program Files\\Jync\\jync.jar.failed-x");
        String script = UpgradeService.buildPowerShellScript(spec);

        assertThat(script).contains("-ArgumentList '-jar \"C:\\Program Files\\Jync\\jync.jar\"'");
        assertThat(script).doesNotContain("@('-jar'");
    }

    /** The script now carries {@code -D} values, which can be credentials. */
    @Test
    void theHelperScriptIsNotReadableByOtherUsers() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = makeFatJar("new");
        feed.release = release(Source.GITEE, "v1.8.0", null);
        UpgradeService svc = serviceWithArgs("1.7.2", downloader,
                List.of("-Dspring.datasource.password=hunter2"), List.of());

        runToReady(svc);
        svc.restart();

        assertThat(Files.getPosixFilePermissions(launcher.script))
                .doesNotContain(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    }

    /** Minimal controllable feed: one canned release or a failure, and latestFrom only. */
    private static final class FakeFeed implements com.qqmu.jync.service.version.SourceAwareFeed {
        Release release;
        Exception failure;
        /** Answers byTagFrom: the OTHER mirror's release for cross-checking a digest. */
        Release otherByTag;
        Exception otherByTagFailure;

        @Override
        public Release latestFrom(Source source) throws Exception {
            if (failure != null) {
                throw failure;
            }
            return release;
        }

        @Override
        public Release byTagFrom(Source source, String tag) throws Exception {
            if (otherByTagFailure != null) {
                throw otherByTagFailure;
            }
            return otherByTag;
        }
    }

    private static Release release(Source source, String tag, Long size) {
        return release(source, tag, size, null);
    }

    private static Release release(Source source, String tag, Long size, String sha256) {
        return new Release(tag,
                "https://example.invalid/" + source + "/" + tag,
                null, "body",
                List.of(new Asset("jync.jar",
                        "https://example.invalid/" + source + "/" + tag + "/jync.jar", size,
                        sha256)),
                source);
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    void aMatchingSha256FromTheFeedLetsTheUpgradeProceed() throws Exception {
        byte[] nextJar = makeFatJar("new-jar-bytes");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITHUB, "v1.8.0", (long) nextJar.length, sha256Of(nextJar));
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITHUB");

        awaitPhase(svc, UpgradeService.Phase.READY);
        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
    }

    @Test
    void aMismatchedSha256FailsClosedAndLeavesTheRunningJarAlone() throws Exception {
        byte[] nextJar = makeFatJar("tampered");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        // A valid zip of the right size, but not the bytes the release claims: this is exactly
        // what the size and zip checks cannot catch.
        feed.release = release(Source.GITHUB, "v1.8.0", (long) nextJar.length,
                "0000000000000000000000000000000000000000000000000000000000000000");
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITHUB");
        awaitPhase(svc, UpgradeService.Phase.FAILED);

        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
        assertThat(tempDir.resolve("app/jync.jar.bak")).doesNotExist();
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        assertThat(svc.status().message().key()).isEqualTo("upgrade.failed.checksumMismatch");
    }

    @Test
    void aMirrorWithoutItsOwnDigestIsCheckedAgainstTheOtherMirrors() throws Exception {
        // Gitee's API publishes no digest, but the same tag on GitHub does. That cross-mirror
        // digest is the only trustworthy checksum available for a Gitee download.
        byte[] nextJar = makeFatJar("cross-checked");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null, null);
        feed.otherByTag = release(Source.GITHUB, "v1.8.0", (long) nextJar.length, sha256Of(nextJar));
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");

        awaitPhase(svc, UpgradeService.Phase.READY);
        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
    }

    @Test
    void aCrossMirrorDigestMismatchFailsClosed() throws Exception {
        byte[] nextJar = makeFatJar("swapped-on-the-mirror");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null, null);
        feed.otherByTag = release(Source.GITHUB, "v1.8.0", null,
                "1111111111111111111111111111111111111111111111111111111111111111");
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");
        awaitPhase(svc, UpgradeService.Phase.FAILED);

        assertThat(Files.readAllBytes(jarFile)).startsWith(new byte[]{'P', 'K'});
        assertThat(tempDir.resolve("app/jync.jar.part")).doesNotExist();
        assertThat(svc.status().message().key()).isEqualTo("upgrade.failed.checksumMismatch");
    }

    @Test
    void whenNoDigestIsAvailableAnywhereTheUpgradeProceedsWithAWarning() throws Exception {
        // Absence of a digest is not a mismatch: blocking every upgrade would break the
        // feature for mirrors that publish none. The computed hash is still logged.
        byte[] nextJar = makeFatJar("unverifiable");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null, null);
        feed.otherByTag = null;
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");

        awaitPhase(svc, UpgradeService.Phase.READY);
        assertThat(Files.readAllBytes(jarFile)).isEqualTo(nextJar);
    }

    @Test
    void aFailedCrossMirrorLookupDoesNotBlockTheUpgrade() throws Exception {
        byte[] nextJar = makeFatJar("lookup-failed");
        ScriptedDownloader downloader = new ScriptedDownloader();
        downloader.blob = nextJar;
        feed.release = release(Source.GITEE, "v1.8.0", null, null);
        feed.otherByTagFailure = new java.io.IOException("offline");
        UpgradeService svc = service("1.7.2", downloader);

        svc.start("GITEE");

        awaitPhase(svc, UpgradeService.Phase.READY);
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

    @Test
    void springBootsNestedClassLocationResolvesToTheOuterJar() throws Exception {
        // `java -jar jync.jar` puts application classes at
        // jar:file:/.../jync.jar!/BOOT-INF/classes!/ — a URL Path.of cannot open.
        URL nested = new URL("jar:file:/opt/jync/jync.jar!/BOOT-INF/classes!/");
        assertThat(UpgradeService.jarPathFromLocation(nested))
                .isEqualTo(Path.of(URI.create("file:/opt/jync/jync.jar")));
    }

    @Test
    void anExplodedClassesDirectoryIsReturnedAsIs() throws Exception {
        Path classes = tempDir.resolve("target/classes");
        assertThat(UpgradeService.jarPathFromLocation(classes.toUri().toURL()))
                .isEqualTo(classes);
    }

    @Test
    void aJarUrlWithoutAnEntrySeparatorIsUnresolvable() throws Exception {
        URL malformed = new URL(null, "jar:file:/opt/jync/jync.jar",
                new java.net.URLStreamHandler() {
                    @Override
                    protected java.net.URLConnection openConnection(URL u) {
                        throw new UnsupportedOperationException();
                    }
                });
        assertThat(UpgradeService.jarPathFromLocation(malformed)).isNull();
    }

    @Test
    void aMissingLocationIsUnresolvable() throws Exception {
        assertThat(UpgradeService.jarPathFromLocation(null)).isNull();
    }
}
