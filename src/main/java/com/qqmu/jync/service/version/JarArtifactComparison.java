package com.qqmu.jync.service.version;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Compares the running jar with a release's published {@code jync.jar}.
 *
 * <p>Two signals, in order of strength:
 * <ol>
 *   <li>the asset's SHA-256 — GitHub reports it in {@code digest}; equality of bytes is exact;</li>
 *   <li>the download URL's {@code Content-Length} — Gitee's API publishes neither digest nor
 *       size, so a HEAD is the only thing left. Different size proves different bytes; the same
 *       size is assumed to mean the same upload (same-size-different-bytes is missed, which is
 *       the price of not downloading 80&nbsp;MB to answer a badge).</li>
 * </ol>
 *
 * <p>The local side is the jar file this JVM was launched from, hashed once and cached. An
 * exploded run (IDE, {@code target/classes}) has no jar to compare and answers UNKNOWN, so
 * developers never see a permanent "artifact updated" badge.
 */
@Slf4j
public class JarArtifactComparison implements ArtifactComparison {

    /** HEADs a download URL and returns its Content-Length, or null when unavailable. */
    @FunctionalInterface
    public interface SizeProbe {
        Long contentLength(String url);
    }

    /** Size and digest of the running jar; {@code null} means "not running from a jar". */
    private record Identity(long sizeBytes, String sha256) {
    }

    private final Supplier<Path> jarLocation;
    private final boolean enabled;
    private final SizeProbe sizeProbe;

    /** Computed on first use — hashing ~80 MB belongs off the boot path, not on every check. */
    private volatile Identity identity;
    private volatile boolean identityLoaded;

    public JarArtifactComparison(Supplier<Path> jarLocation, boolean enabled,
                                 SizeProbe sizeProbe) {
        this.jarLocation = jarLocation;
        this.enabled = enabled;
        this.sizeProbe = sizeProbe;
    }

    @Override
    public Result compare(ReleaseFeed.Release release) {
        if (!enabled || release == null) {
            return Result.UNKNOWN;
        }
        ReleaseFeed.Asset asset = release.jarAsset(ReleaseFeed.JAR_ASSET_NAME);
        if (asset == null || !isTrustedDownloadUrl(asset.url())) {
            return Result.UNKNOWN;
        }
        Identity local = localIdentity();
        if (local == null) {
            return Result.UNKNOWN;
        }
        if (StringUtils.hasText(asset.sha256())) {
            return asset.sha256().trim().equalsIgnoreCase(local.sha256())
                    ? Result.SAME : Result.DIFFERENT;
        }
        Long remoteSize = sizeProbe.contentLength(asset.url());
        if (remoteSize == null) {
            log.debug("Artifact comparison: no size for {}", asset.url());
            return Result.UNKNOWN;
        }
        return remoteSize == local.sizeBytes() ? Result.SAME : Result.DIFFERENT;
    }

    private Identity localIdentity() {
        if (identityLoaded) {
            return identity;
        }
        synchronized (this) {
            if (!identityLoaded) {
                identity = readIdentity();
                identityLoaded = true;
            }
            return identity;
        }
    }

    private Identity readIdentity() {
        Path jar = jarLocation.get();
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        try {
            return new Identity(Files.size(jar), sha256(jar));
        } catch (Exception e) {
            log.debug("Artifact comparison: cannot hash {}: {}", jar, e.toString());
            return null;
        }
    }

    /**
     * Only the two mirrors this app ships from. The URL arrives in feed JSON and the probe
     * sends a request to it, so anything else — a typo, a hostile payload — stays untouched.
     */
    static boolean isTrustedDownloadUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim();
        return u.startsWith("https://github.com/") || u.startsWith("https://gitee.com/");
    }

    /** Streaming hex SHA-256; the jar is far too large to read into a byte array. */
    static String sha256(Path file) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
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

    /** HEAD probe used in production; redirects are followed (mirror downloads 302 to storage). */
    static SizeProbe httpProbe(Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "jync-update-check")
                        .build();
                var response = http.send(request, BodyHandlers.discarding());
                if (response.statusCode() != 200) {
                    return null;
                }
                OptionalLong length = response.headers().firstValueAsLong("Content-Length");
                return length.isPresent() ? length.getAsLong() : null;
            } catch (Exception e) {
                log.debug("Artifact comparison: HEAD {} failed: {}", url, e.toString());
                return null;
            }
        };
    }

    @Component
    public static class Bean extends JarArtifactComparison {
        public Bean(@Value("${app.update-check.match-artifact:true}") boolean enabled,
                    @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
            super(RunningJar::locate, enabled, httpProbe(Duration.ofMillis(timeoutMs)));
        }
    }

}
