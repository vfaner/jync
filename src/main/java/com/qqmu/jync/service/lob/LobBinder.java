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
        if (dialect == null || value == null || targetType == null) {
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
            clob.setString(1, text.toString());
            ps.setClob(index, clob);
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
            blob.setBytes(1, bytes);
            ps.setBlob(index, blob);
            if (tracker != null) {
                tracker.track(blob);
            }
            return true;
        }
        return false;
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
