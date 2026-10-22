package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates one spill directory per configured location and hands out per-copy sessions.
 * The store holds no state worth cleaning up; the session owns every file it writes.
 */
public final class LobSpillStore {

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

    public LobSpillSession open() {
        return new LobSpillSession(this);
    }

    Path directory() {
        return dir;
    }

    long maxBytes() {
        return maxBytes;
    }
}
