package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A value written to a temp file so it can be bound repeatedly without holding the whole
 * thing in the heap. The file is deleted by {@link #release()}, which every writer path
 * must call — success, rollback or exception.
 */
final class SpilledLobValue implements LobValue {

    private final Path file;
    private final Kind kind;
    private final long length;
    private final AtomicBoolean released = new AtomicBoolean();

    SpilledLobValue(Path file, Kind kind, long length) {
        this.file = file;
        this.kind = kind;
        this.length = length;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public boolean replayable() {
        return true;
    }

    @Override
    public Reader openReader() throws IOException {
        if (kind != Kind.TEXT) {
            throw new UnsupportedOperationException("binary LOB has no character reader");
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    @Override
    public InputStream openStream() throws IOException {
        if (kind != Kind.BINARY) {
            throw new UnsupportedOperationException("text LOB has no binary stream");
        }
        return Files.newInputStream(file);
    }

    Path file() {
        return file;
    }

    @Override
    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Startup cleanup sweeps leftovers under the spill directory.
        }
    }
}
