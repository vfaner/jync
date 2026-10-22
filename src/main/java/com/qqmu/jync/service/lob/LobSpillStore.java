package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.qqmu.jync.config.SyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates one spill directory per configured location and hands out per-copy sessions.
 *
 * <p>The session owns every file it writes; the store owns the set of sessions that are
 * still open. That set exists for shutdown and for the startup sweep: a JVM killed
 * mid-transaction leaves {@code jync-lob-*} files no live process will ever release, and
 * the next start deletes the ones older than {@code sync.lob.spill-retention-hours}.
 */
@Slf4j
public final class LobSpillStore {

    /** Every open session across all stores — one JVM-wide registry for shutdown. */
    private static final Set<LobSpillSession> ACTIVE = ConcurrentHashMap.newKeySet();

    private final Path dir;
    private final long maxBytes;

    private LobSpillStore(Path dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = Math.max(0L, maxBytes);
    }

    public static LobSpillStore of(Path dir, long maxBytes) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create LOB spill directory " + dir, e);
        }
        return new LobSpillStore(dir, maxBytes);
    }

    /** The store described by the application's {@code sync.lob} settings. */
    public static LobSpillStore from(SyncProperties properties) {
        SyncProperties.Lob lob = properties.getLob();
        return of(java.nio.file.Paths.get(lob.getSpillDir()), lob.getSpillMaxBytes());
    }

    public LobSpillSession open() {
        LobSpillSession session = new LobSpillSession(this);
        ACTIVE.add(session);
        return session;
    }

    void sessionClosed(LobSpillSession session) {
        ACTIVE.remove(session);
    }

    /** Number of sessions still holding files; exposed for tests and shutdown diagnostics. */
    public int activeSessions() {
        return ACTIVE.size();
    }

    /**
     * Deletes spill files in this directory that are older than {@code retentionHours} and
     * carry this application's prefix. Foreign files are never touched — the directory is
     * configurable and may be shared. A non-positive retention disables the sweep.
     */
    public int sweepOldFiles(long retentionHours) {
        if (retentionHours <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith(LobSpillSession.FILE_PREFIX)) {
                    continue;
                }
                try {
                    FileTime modified = Files.getLastModifiedTime(file);
                    if (modified.toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("Could not sweep stale LOB spill file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not list LOB spill directory {}: {}", dir, e.getMessage());
        }
        return deleted;
    }

    /** Closes every session this JVM still has open (shutdown path); idempotent. */
    public static void closeAllActiveSessions() {
        for (LobSpillSession session : new ArrayList<>(ACTIVE)) {
            session.close();
        }
    }

    Path directory() {
        return dir;
    }

    long maxBytes() {
        return maxBytes;
    }
}
