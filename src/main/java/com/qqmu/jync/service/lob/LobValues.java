package com.qqmu.jync.service.lob;

import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Path;

/** Entry points for building LOB values, plus null-safe helpers for the writer paths. */
public final class LobValues {

    private LobValues() {
    }

    public static LobValue text(String value) {
        return InMemoryLobValue.text(value);
    }

    public static LobValue binary(byte[] value) {
        return InMemoryLobValue.binary(value);
    }

    public static LobValue oneShotText(Reader reader, long length) {
        return new OneShotLobValue(LobValue.Kind.TEXT, length, reader, null);
    }

    public static LobValue oneShotBinary(InputStream stream, long length) {
        return new OneShotLobValue(LobValue.Kind.BINARY, length, null, stream);
    }

    public static LobValue spilled(Path file, LobValue.Kind kind, long length) {
        return new SpilledLobValue(file, kind, length);
    }

    public static boolean isLob(Object value) {
        return value instanceof LobValue;
    }

    /**
     * Ordinary values are always replayable; only a {@link OneShotLobValue} is not.
     * Callers bind an ordinary value as many times as they like.
     */
    public static boolean isReplayable(Object value) {
        return !(value instanceof LobValue) || ((LobValue) value).replayable();
    }

    /** Whether the row must be executed before the result set cursor advances. */
    public static boolean requiresImmediateExecution(Object value) {
        return value instanceof LobValue && !((LobValue) value).replayable();
    }

    public static void release(Object value) {
        if (value instanceof LobValue) {
            ((LobValue) value).release();
        }
    }
}
