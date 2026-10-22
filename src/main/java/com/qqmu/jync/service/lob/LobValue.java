package com.qqmu.jync.service.lob;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * A column value that is too large to carry around as a plain {@code String} or
 * {@code byte[]}.
 *
 * <p>The three implementations trade memory against reusability: in-memory values are
 * small enough to keep, spilled values sit in a temp file and can be bound repeatedly,
 * and one-shot values wrap a live result-set stream that the driver invalidates as soon
 * as the cursor advances.
 */
public interface LobValue extends AutoCloseable {

    enum Kind { TEXT, BINARY }

    Kind kind();

    /**
     * Characters for {@link Kind#TEXT}, bytes for {@link Kind#BINARY};
     * {@code -1} when the source could not tell us.
     */
    long length();

    /**
     * Whether {@link #openReader()} / {@link #openStream()} may be called more than once.
     * Emulated upserts and CDC replay bind the same value several times and therefore
     * require a replayable value.
     */
    boolean replayable();

    Reader openReader() throws IOException;

    InputStream openStream() throws IOException;

    /** Idempotent; deletes spill files and closes source streams. */
    void release();

    @Override
    default void close() {
        release();
    }
}
