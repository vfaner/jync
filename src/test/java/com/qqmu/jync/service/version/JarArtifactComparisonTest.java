package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.service.version.ReleaseFeed.Asset;
import com.qqmu.jync.service.version.ReleaseFeed.Release;

/**
 * "Is the jar published under this tag the same bytes I am running?" — decided without
 * downloading it. A version number cannot answer this: v1.8.0 was re-uploaded three times
 * under the same tag, and every host running an earlier upload still reported "up to date".
 *
 * <p>The digest is the only sound signal, and only GitHub publishes one. Gitee's API carries
 * neither digest nor size, so the fallback is a HEAD on the download URL and a byte-count
 * comparison — weaker (same size, different bytes is missed) but cheap and honest about it.
 */
class JarArtifactComparisonTest {

    private static final String GITHUB_JAR =
            "https://github.com/vfaner/jync/releases/download/v1.8.0/jync.jar";

    @TempDir
    Path dir;

    Path jar;
    AtomicInteger probes;

    @BeforeEach
    void setUp() throws Exception {
        jar = dir.resolve("jync.jar");
        Files.writeString(jar, "the running bytes", StandardCharsets.UTF_8);
        probes = new AtomicInteger();
    }

    private JarArtifactComparison comparison(boolean enabled, Long headLength) {
        return new JarArtifactComparison(() -> jar, enabled, url -> {
            probes.incrementAndGet();
            return headLength;
        });
    }

    private static Release release(Asset... assets) {
        return new Release("v1.8.0", "https://github.com/vfaner/jync/releases/tag/v1.8.0",
                Instant.parse("2026-10-25T01:07:45Z"), "body", List.of(assets),
                ReleaseFeed.Source.GITHUB);
    }

    private static String sha256Of(Path file) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Test
    void matchingDigestIsSame() throws Exception {
        JarArtifactComparison cmp = comparison(true, null);

        assertThat(cmp.compare(release(new Asset("jync.jar", GITHUB_JAR, 16L,
                sha256Of(jar).toUpperCase())))).isEqualTo(ArtifactComparison.Result.SAME);
        // The digest decides: no HEAD is spent when the feed already published one.
        assertThat(probes).hasValue(0);
    }

    @Test
    void differentDigestIsDifferent() {
        JarArtifactComparison cmp = comparison(true, null);

        assertThat(cmp.compare(release(new Asset("jync.jar", GITHUB_JAR, 16L, "ab".repeat(32)))))
                .isEqualTo(ArtifactComparison.Result.DIFFERENT);
        assertThat(probes).hasValue(0);
    }

    @Test
    void withoutADigestTheByteCountDecides() throws Exception {
        long size = Files.size(jar);

        assertThat(comparison(true, size).compare(release(new Asset("jync.jar", GITHUB_JAR, null))))
                .isEqualTo(ArtifactComparison.Result.SAME);
        assertThat(comparison(true, size + 1)
                .compare(release(new Asset("jync.jar", GITHUB_JAR, null))))
                .isEqualTo(ArtifactComparison.Result.DIFFERENT);
        assertThat(probes).hasValue(2);
    }

    @Test
    void aFailedHeadLeavesTheQuestionUnanswered() {
        assertThat(comparison(true, null)
                .compare(release(new Asset("jync.jar", GITHUB_JAR, null))))
                .isEqualTo(ArtifactComparison.Result.UNKNOWN);
    }

    @Test
    void runningFromAnExplodedTreeCannotBeCompared() {
        JarArtifactComparison cmp = new JarArtifactComparison(
                () -> dir.resolve("classes"), true, url -> 16L);

        assertThat(cmp.compare(release(new Asset("jync.jar", GITHUB_JAR, 16L, "ab".repeat(32)))))
                .isEqualTo(ArtifactComparison.Result.UNKNOWN);
    }

    @Test
    void theSwitchTurnsComparisonOffEntirely() {
        JarArtifactComparison cmp = comparison(false, 16L);

        assertThat(cmp.compare(release(new Asset("jync.jar", GITHUB_JAR, 16L, "ab".repeat(32)))))
                .isEqualTo(ArtifactComparison.Result.UNKNOWN);
        assertThat(probes).hasValue(0);
    }

    @Test
    void aReleaseWithoutTheJarAssetIsNotCompared() {
        assertThat(comparison(true, 16L)
                .compare(release(new Asset("notes.txt", GITHUB_JAR, 1L))))
                .isEqualTo(ArtifactComparison.Result.UNKNOWN);
        assertThat(probes).hasValue(0);
    }

    @Test
    void aDownloadUrlOnAnotherHostIsNeverProbed() {
        // The HEAD goes to whatever URL the feed JSON names; anything but the two known
        // mirrors stays untouched even when the payload is otherwise well formed.
        assertThat(comparison(true, 16L)
                .compare(release(new Asset("jync.jar", "https://evil.example/jync.jar", null))))
                .isEqualTo(ArtifactComparison.Result.UNKNOWN);
        assertThat(probes).hasValue(0);
    }
}
