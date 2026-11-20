package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the read-only startup check for a second Jync instance on the same metadata store.
 *
 * <p>The failure these exist for: a one-click upgrade whose old process had not fully exited
 * let two JVMs open {@code ./data/jync} at once, and the second one died with Hibernate's
 * generic "Unable to determine Dialect without JDBC metadata". The lock file H2 leaves next to
 * the store names the live holder's TCP server (AUTO_SERVER writes {@code server=host:port});
 * reading it is enough to warn the operator before the store is opened.
 */
class MetadataStoreLockTest {

    private static final String URL = "jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE";

    @TempDir
    Path tempDir;

    @Test
    void noLockFileMeansNothingToReport() {
        assertThat(MetadataStoreLock.inspect(URL, tempDir, (host, port) -> true)).isEmpty();
        assertThat(MetadataStoreLock.startupWarning(URL, tempDir, (host, port) -> true)).isEmpty();
    }

    @Test
    void aRelativeStoreIsResolvedAgainstTheWorkingDirectory() throws IOException {
        Path lock = writeLock("id=123\nmethod=file\nserver=192.168.1.10:9092\n");

        MetadataStoreLock.Evidence evidence =
                MetadataStoreLock.inspect(URL, tempDir, (host, port) -> false).orElseThrow();

        assertThat(evidence.lockFile()).isEqualTo(lock);
        assertThat(evidence.serverAddress()).isEqualTo("192.168.1.10:9092");
    }

    @Test
    void anAbsoluteStoreUrlIsUsedAsIs() throws IOException {
        String absolute = "jdbc:h2:file:" + tempDir.resolve("store/jync") + ";AUTO_SERVER=TRUE";
        Files.createDirectories(tempDir.resolve("store"));
        Path lock = tempDir.resolve("store/jync.lock.db");
        Files.writeString(lock, "server=127.0.0.1:9123\n");

        MetadataStoreLock.Evidence evidence =
                MetadataStoreLock.inspect(absolute, Path.of("/elsewhere"), (host, port) -> false)
                        .orElseThrow();

        assertThat(evidence.lockFile()).isEqualTo(lock);
    }

    @Test
    void theAdvertisedServerIsParsedAndProbed() throws IOException {
        writeLock("id=123\nserver=192.168.1.10:9092\n");
        AtomicReference<String> probedHost = new AtomicReference<>();
        AtomicInteger probedPort = new AtomicInteger();

        MetadataStoreLock.Evidence evidence = MetadataStoreLock.inspect(URL, tempDir, (host, port) -> {
            probedHost.set(host);
            probedPort.set(port);
            return true;
        }).orElseThrow();

        assertThat(probedHost.get()).isEqualTo("192.168.1.10");
        assertThat(probedPort.get()).isEqualTo(9092);
        assertThat(evidence.serverAnswered()).isTrue();
    }

    @Test
    void aLiveHolderIsNamedInTheWarning() throws IOException {
        Path lock = writeLock("id=123\nserver=192.168.1.10:9092\n");

        String warning = MetadataStoreLock.startupWarning(URL, tempDir, (host, port) -> true).orElseThrow();

        assertThat(warning).contains("另一个 Jync 实例", lock.toString(), "192.168.1.10:9092");
        assertThat(warning).doesNotContain("残留锁");
    }

    @Test
    void aLockFileWhoseServerDoesNotAnswerIsReportedAsLeftOver() throws IOException {
        Path lock = writeLock("id=123\nserver=192.168.1.10:9092\n");

        String warning = MetadataStoreLock.startupWarning(URL, tempDir, (host, port) -> false).orElseThrow();

        assertThat(warning).contains(lock.toString(), "残留锁", "删除该文件");
        assertThat(warning).doesNotContain("另一个 Jync 实例");
    }

    @Test
    void aLockFileWithoutAServerPropertyIsNotProbed() throws IOException {
        // AUTO_SERVER may be switched off in a custom URL; the lock file then names no TCP
        // server, but still proves another process (or a killed one) had the store open.
        Path lock = writeLock("id=123\nmethod=file\n");
        AtomicInteger probes = new AtomicInteger();

        String warning = MetadataStoreLock.startupWarning(URL, tempDir, (host, port) -> {
            probes.incrementAndGet();
            return true;
        }).orElseThrow();

        assertThat(probes.get()).isZero();
        assertThat(warning).contains(lock.toString(), "残留锁");
    }

    @Test
    void nonFileUrlsAreIgnored() {
        assertThat(MetadataStoreLock.inspect("jdbc:h2:mem:test", tempDir, (host, port) -> true)).isEmpty();
        assertThat(MetadataStoreLock.inspect(null, tempDir, (host, port) -> true)).isEmpty();
    }

    private Path writeLock(String content) throws IOException {
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Path lock = data.resolve("jync.lock.db");
        Files.writeString(lock, content);
        return lock;
    }

    @Test
    void theRealProbeTreatsAClosedPortAsDead() {
        // Port 1 on loopback is not listening; the probe must fail fast instead of hanging.
        assertThat(MetadataStoreLock.serverAnswers("127.0.0.1", 1)).isFalse();
    }
}
