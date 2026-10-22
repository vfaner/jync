package com.qqmu.jync.service.lob;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;

/**
 * Turns a driver stream into the value the writer should hold.
 *
 * <p>The first {@code inlineMaxBytes} are buffered so ordinary values stay plain
 * {@code String}/{@code byte[]} and keep the batch path fast. Only when the value crosses
 * that threshold does it become a {@link OneShotLobValue} (flush and execute this row
 * alone) or a {@link SpilledLobValue} (replayable through a temp file).
 */
public final class LobStreams {

    private static final int CHUNK = 8192;

    private LobStreams() {
    }

    public static Object readText(Reader source, LobReadPolicy policy, LobSpillSession session)
            throws IOException {
        StringBuilder prefix = new StringBuilder();
        long bytes = 0;
        boolean overflow = false;
        char[] buffer = new char[CHUNK];
        try {
            int n;
            while ((n = source.read(buffer)) != -1) {
                prefix.append(buffer, 0, n);
                for (int i = 0; i < n; i++) {
                    bytes += utf8Bytes(buffer[i]);
                }
                if (bytes > policy.inlineMaxBytes()) {
                    overflow = true;
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(source);
            throw e;
        }
        if (!overflow) {
            closeQuietly(source);
            return prefix.toString();
        }
        Reader combined = new ConcatReader(new StringReader(prefix.toString()), source);
        if (policy.largeMode() == LobReadPolicy.Mode.SPILL) {
            if (session == null) {
                closeQuietly(combined);
                throw new IllegalStateException(
                        "SPILL read policy requires a LobSpillSession");
            }
            return session.spillText(combined);
        }
        return LobValues.oneShotText(combined, -1);
    }

    public static Object readBinary(InputStream source, LobReadPolicy policy,
                                    LobSpillSession session) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        boolean overflow = false;
        byte[] buffer = new byte[CHUNK];
        try {
            int n;
            while ((n = source.read(buffer)) != -1) {
                prefix.write(buffer, 0, n);
                if (prefix.size() > policy.inlineMaxBytes()) {
                    overflow = true;
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(source);
            throw e;
        }
        if (!overflow) {
            closeQuietly(source);
            return prefix.toByteArray();
        }
        InputStream combined = new ConcatInputStream(
                new ByteArrayInputStream(prefix.toByteArray()), source);
        if (policy.largeMode() == LobReadPolicy.Mode.SPILL) {
            if (session == null) {
                closeQuietly(combined);
                throw new IllegalStateException(
                        "SPILL read policy requires a LobSpillSession");
            }
            return session.spillBinary(combined);
        }
        return LobValues.oneShotBinary(combined, -1);
    }

    private static long utf8Bytes(char c) {
        if (c < 0x80) {
            return 1;
        }
        if (c < 0x800) {
            return 2;
        }
        // Surrogates are counted as three bytes each; a pair really takes four, so the
        // estimate can only over-count, never under-count.
        return 3;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort.
        }
    }
}
