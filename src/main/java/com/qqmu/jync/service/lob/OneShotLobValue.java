package com.qqmu.jync.service.lob;

import java.io.InputStream;
import java.io.Reader;

/**
 * A single-use value backed by a live result-set stream: valid only until the cursor
 * moves, so the writer must bind and execute it before reading the next row.
 */
final class OneShotLobValue implements LobValue {

    private final Kind kind;
    private final long length;
    private Reader reader;
    private InputStream stream;
    private boolean consumed;
    private boolean released;

    OneShotLobValue(Kind kind, long length, Reader reader, InputStream stream) {
        this.kind = kind;
        this.length = length;
        this.reader = reader;
        this.stream = stream;
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
        return false;
    }

    @Override
    public synchronized Reader openReader() {
        if (kind != Kind.TEXT) {
            throw new UnsupportedOperationException("binary LOB has no character reader");
        }
        if (consumed) {
            throw new IllegalStateException("one-shot LOB has already been read");
        }
        consumed = true;
        return reader;
    }

    @Override
    public synchronized InputStream openStream() {
        if (kind != Kind.BINARY) {
            throw new UnsupportedOperationException("text LOB has no binary stream");
        }
        if (consumed) {
            throw new IllegalStateException("one-shot LOB has already been read");
        }
        consumed = true;
        return stream;
    }

    @Override
    public synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        closeQuietly(reader);
        closeQuietly(stream);
        reader = null;
        stream = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Releasing is best-effort; the connection close will clean up the rest.
        }
    }
}
