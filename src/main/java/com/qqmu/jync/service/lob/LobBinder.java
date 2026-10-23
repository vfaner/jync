package com.qqmu.jync.service.lob;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;

import com.qqmu.jync.service.converter.SqlDialect;

import lombok.extern.slf4j.Slf4j;

/**
 * Binds values the target dialect cannot take inline through a server-side LOB handle.
 *
 * <p>The caller passes the value it would otherwise hand to {@code setObject}; returning
 * {@code true} means the value is already bound and the caller must not bind it again.
 * Only values that genuinely need a handle take it: everything else falls through to the
 * existing binding, so a normal sync behaves exactly as before.
 */
@Slf4j
public final class LobBinder {

    /** Collects the temporary LOBs created for one batch, so they can be freed together. */
    public interface Tracker {
        void track(Clob clob);

        void track(Blob blob);

        /** Frees every tracked LOB; safe to call more than once. */
        void releaseAll();
    }

    /** For paths that never create temporary LOBs. */
    public static final Tracker NOOP = new Tracker() {
        @Override
        public void track(Clob clob) {
        }

        @Override
        public void track(Blob blob) {
        }

        @Override
        public void releaseAll() {
        }
    };

    private LobBinder() {
    }

    public static Tracker newTracker() {
        return new Tracking();
    }

    /**
     * Binds {@code value} as a temporary LOB when the dialect's inline ceiling is exceeded,
     * or when an empty string must stay non-null in a LOB column.
     *
     * @return true when the value was bound here; false when the caller should bind it
     */
    public static boolean bind(PreparedStatement ps, int index, Object value,
                               @Nullable Integer targetType, @Nullable SqlDialect dialect,
                               @Nullable Tracker tracker) throws SQLException {
        if (dialect == null || value == null) {
            return false;
        }
        if (value instanceof LobValue) {
            return bindLobValue(ps, index, (LobValue) value, dialect, tracker);
        }
        if (targetType == null) {
            return false;
        }
        if (value instanceof CharSequence && isTextLob(targetType)) {
            CharSequence text = (CharSequence) value;
            boolean emptyNeedsLob = text.length() == 0 && dialect.bindsEmptyStringAsLob();
            boolean aboveCeiling = utf8ByteLength(text) > dialect.maxInlineBindBytes();
            if (!emptyNeedsLob && !aboveCeiling) {
                return false;
            }
            Clob clob = ps.getConnection().createClob();
            boolean bound = false;
            try {
                clob.setString(1, text.toString());
                ps.setClob(index, clob);
                bound = true;
            } finally {
                // A temporary LOB occupies the target's temp tablespace from the moment it
                // exists; anything short of handing it to the tracker must free it here.
                if (!bound) {
                    freeQuietly(clob);
                }
            }
            if (tracker != null) {
                tracker.track(clob);
            }
            return true;
        }
        if (value instanceof byte[] && isBinaryLob(targetType)) {
            byte[] bytes = (byte[]) value;
            if (bytes.length <= dialect.maxInlineBindBytes()) {
                return false;
            }
            Blob blob = ps.getConnection().createBlob();
            boolean bound = false;
            try {
                blob.setBytes(1, bytes);
                ps.setBlob(index, blob);
                bound = true;
            } finally {
                if (!bound) {
                    freeQuietly(blob);
                }
            }
            if (tracker != null) {
                tracker.track(blob);
            }
            return true;
        }
        return false;
    }

    /**
     * A value that already gave up being a {@code String}/{@code byte[]}. Oracle gets a
     * copy in a temporary LOB; every other target gets the stream and reads it at execute
     * time, which is why a non-replayable value must be executed before the cursor moves.
     */
    private static boolean bindLobValue(PreparedStatement ps, int index, LobValue lob,
                                        SqlDialect dialect, @Nullable Tracker tracker)
            throws SQLException {
        try {
            if (lob.kind() == LobValue.Kind.TEXT) {
                java.io.Reader reader = lob.openReader();
                if (dialect.requiresServerSideLob()) {
                    Clob clob = ps.getConnection().createClob();
                    boolean bound = false;
                    try {
                        try (java.io.Writer out = clob.setCharacterStream(1)) {
                            copy(reader, out);
                        } catch (java.io.IOException e) {
                            throw new SQLException("Failed to fill a temporary CLOB", e);
                        }
                        ps.setClob(index, clob);
                        bound = true;
                    } finally {
                        closeQuietly(reader);
                        if (!bound) {
                            freeQuietly(clob);
                        }
                    }
                    if (tracker != null) {
                        tracker.track(clob);
                    }
                } else if (lob.length() > 0) {
                    // The int overload on purpose: pgjdbc and the openGauss fork both throw
                    // "not implemented" from setCharacterStream(int, Reader, long) — only the
                    // int one reads the reader. A length above int range goes to the long
                    // overload because no other driver can take it either way.
                    if (lob.length() <= Integer.MAX_VALUE) {
                        ps.setCharacterStream(index, reader, (int) lob.length());
                    } else {
                        ps.setCharacterStream(index, reader, lob.length());
                    }
                } else {
                    // Length 0 means "unknown" to the driver, which is exactly the case
                    // for a one-shot value read from a result set.
                    ps.setCharacterStream(index, reader);
                }
                return true;
            }
            java.io.InputStream stream = lob.openStream();
            if (dialect.requiresServerSideLob()) {
                Blob blob = ps.getConnection().createBlob();
                boolean bound = false;
                try {
                    try (java.io.OutputStream out = blob.setBinaryStream(1)) {
                        copy(stream, out);
                    } catch (java.io.IOException e) {
                        throw new SQLException("Failed to fill a temporary BLOB", e);
                    }
                    ps.setBlob(index, blob);
                    bound = true;
                } finally {
                    closeQuietly(stream);
                    if (!bound) {
                        freeQuietly(blob);
                    }
                }
                if (tracker != null) {
                    tracker.track(blob);
                }
            } else if (lob.length() > 0) {
                ps.setBinaryStream(index, stream, lob.length());
            } else {
                ps.setBinaryStream(index, stream);
            }
            return true;
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to stream a LOB value", e);
        }
    }

    private static void copy(java.io.Reader reader, java.io.Writer out)
            throws java.io.IOException {
        char[] buffer = new char[8192];
        int n;
        while ((n = reader.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out)
            throws java.io.IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The writer releases the value again after the batch executes.
        }
    }

    private static void freeQuietly(Clob clob) {
        try {
            clob.free();
        } catch (SQLException e) {
            log.debug("Freeing an unbound temporary CLOB failed: {}", e.getMessage());
        }
    }

    private static void freeQuietly(Blob blob) {
        try {
            blob.free();
        } catch (SQLException e) {
            log.debug("Freeing an unbound temporary BLOB failed: {}", e.getMessage());
        }
    }

    /**
     * UTF-8 byte count without allocating an encoded copy. Used as the ceiling measure
     * because it is never smaller than the UTF-16 or GBK form: a value that fits by this
     * count fits in every server charset Jync targets.
     */
    public static long utf8ByteLength(CharSequence text) {
        long bytes = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static boolean isTextLob(int jdbcType) {
        return jdbcType == Types.CLOB || jdbcType == Types.NCLOB
                || jdbcType == Types.LONGVARCHAR || jdbcType == Types.LONGNVARCHAR;
    }

    private static boolean isBinaryLob(int jdbcType) {
        return jdbcType == Types.BLOB || jdbcType == Types.LONGVARBINARY;
    }

    private static final class Tracking implements Tracker {
        private final List<Clob> clobs = new ArrayList<>();
        private final List<Blob> blobs = new ArrayList<>();

        @Override
        public void track(Clob clob) {
            clobs.add(clob);
        }

        @Override
        public void track(Blob blob) {
            blobs.add(blob);
        }

        @Override
        public void releaseAll() {
            for (Clob clob : clobs) {
                try {
                    clob.free();
                } catch (SQLException e) {
                    // Best effort: the connection's own cleanup frees it eventually.
                    log.debug("Freeing temporary CLOB failed: {}", e.getMessage());
                }
            }
            clobs.clear();
            for (Blob blob : blobs) {
                try {
                    blob.free();
                } catch (SQLException e) {
                    log.debug("Freeing temporary BLOB failed: {}", e.getMessage());
                }
            }
            blobs.clear();
        }
    }
}
