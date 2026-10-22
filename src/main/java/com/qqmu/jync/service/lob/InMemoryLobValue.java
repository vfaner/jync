package com.qqmu.jync.service.lob;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/** A value small enough to keep in the heap, but routed through the LOB path anyway. */
final class InMemoryLobValue implements LobValue {

    private final Kind kind;
    private final String text;
    private final byte[] bytes;

    private InMemoryLobValue(Kind kind, String text, byte[] bytes) {
        this.kind = kind;
        this.text = text;
        this.bytes = bytes;
    }

    static InMemoryLobValue text(String value) {
        return new InMemoryLobValue(Kind.TEXT, value == null ? "" : value, null);
    }

    static InMemoryLobValue binary(byte[] value) {
        return new InMemoryLobValue(Kind.BINARY, null, value == null ? new byte[0] : value);
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public long length() {
        return kind == Kind.TEXT ? text.length() : bytes.length;
    }

    @Override
    public boolean replayable() {
        return true;
    }

    @Override
    public Reader openReader() {
        if (kind != Kind.TEXT) {
            throw new UnsupportedOperationException("binary LOB has no character reader");
        }
        return new StringReader(text);
    }

    @Override
    public InputStream openStream() throws IOException {
        if (kind != Kind.BINARY) {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void release() {
        // Heap data is reclaimed by the GC once the row is dropped.
    }
}
