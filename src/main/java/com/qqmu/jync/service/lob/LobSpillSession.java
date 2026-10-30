package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the temp files written for one copy (or one CDC transaction).
 *
 * <p>Closing the session releases every value it handed out, so a failure path only has to
 * close the session — it does not have to know which columns spilled. The byte cap is per
 * session to stop one pathological table from filling the disk.
 */
public final class LobSpillSession implements AutoCloseable {

    static final String FILE_PREFIX = "jync-lob-";

    private final LobSpillStore store;
    private final List<LobValue> values = new ArrayList<>();
    private long spilledBytes;
    private boolean closed;

    LobSpillSession(LobSpillStore store) {
        this.store = store;
    }

    public synchronized LobValue spillText(Reader source) throws IOException {
        ensureOpen();
        Path file = newFile();
        long chars = 0;
        long estimatedBytes = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int n;
            while ((n = source.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                chars += n;
                // Exact UTF-8 size of what has been read so far, so the cap means the real
                // disk usage. Counting a flat three bytes per char (the BMP worst case)
                // made an ASCII value trip at a third of the configured limit; the file
                // size itself cannot be used because the writer buffers.
                estimatedBytes += LobBinder.utf8ByteLength(CharBuffer.wrap(buffer, 0, n));
                checkCap(estimatedBytes, file);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        } finally {
            closeQuietly(source);
        }
        spilledBytes += Files.size(file);
        return track(LobValues.spilled(file, LobValue.Kind.TEXT, chars));
    }

    public synchronized LobValue spillBinary(InputStream source) throws IOException {
        ensureOpen();
        Path file = newFile();
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                checkCap(Files.size(file), file);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        } finally {
            closeQuietly(source);
        }
        long length = Files.size(file);
        spilledBytes += length;
        return track(LobValues.spilled(file, LobValue.Kind.BINARY, length));
    }

    public synchronized long spilledBytes() {
        return spilledBytes;
    }

    private void checkCap(long fileBytes, Path file) {
        if (store.maxBytes() > 0 && spilledBytes + fileBytes > store.maxBytes()) {
            throw new LobLimitExceededException("LOB spill in " + store.directory()
                    + " exceeded sync.lob.spill-max-bytes=" + store.maxBytes()
                    + " (file " + file.getFileName() + "); raise the limit or narrow the"
                    + " sync's column selection.");
        }
    }

    private Path newFile() throws IOException {
        return Files.createTempFile(store.directory(), FILE_PREFIX, ".lob");
    }

    private LobValue track(LobValue value) {
        values.add(value);
        return value;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LOB spill session is already closed");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The startup sweep picks up leftovers.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (LobValue value : values) {
            value.release();
        }
        values.clear();
        store.sessionClosed(this);
    }
}
