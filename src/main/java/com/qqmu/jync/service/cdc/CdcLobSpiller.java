package com.qqmu.jync.service.cdc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.service.lob.LobBinder;
import com.qqmu.jync.service.lob.LobSpillSession;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValue;
import com.qqmu.jync.service.sync.ColumnCleanser;

import lombok.extern.slf4j.Slf4j;

/**
 * Replaces oversized cells in a buffered CDC row with a replayable on-disk handle.
 *
 * <p>CDC buffers a whole source transaction in memory until the commit boundary. One
 * {@code LONGTEXT} row is harmless; a bulk load of them is not. Values above
 * {@code sync.lob.cdc-spill-threshold-bytes} are written to the spill directory and the
 * buffer keeps only the handle. The write side binds handles like any other LOB value, and
 * because a spilled value is replayable it can be bound more than once (emulated upsert,
 * at-least-once replay).
 *
 * <p>Two kinds of column are deliberately left in memory: primary keys (the transaction
 * fold compares them and the delete path re-binds them) and columns a hand-written column
 * rule touches (the rules are String-only, so a streamed value would silently skip them).
 * Those are exactly the columns the polling engine force-inlines.
 *
 * <p>Ownership: one spiller spans one buffered transaction. Its owner closes it on every
 * path that clears the buffer; a failed flush keeps it open so the retained buffer stays
 * readable, and {@link #close()} releases everything at once.
 */
@Slf4j
public final class CdcLobSpiller implements AutoCloseable {

    private final LobSpillStore store;
    private final long thresholdBytes;
    private final SyncConfig config;
    /**
     * Where the table's compiled column rules come from. A spiller lives one transaction,
     * but the rules are a pure function of the configuration: the owning holder passes a
     * run-scoped cache so a stream that spills every commit compiles each rule once.
     */
    private final Function<String, ColumnCleanser> cleansers;

    /** binding -> upper-cased names of columns that must stay in memory (PKs + ruled). */
    private final Map<CdcTableBinding, Set<String>> forceInline = new HashMap<>();

    private LobSpillSession session;
    private boolean closed;

    /** A spiller that changes nothing; keeps every existing call site on the old path. */
    public static CdcLobSpiller disabled() {
        return new CdcLobSpiller(null, 0L, null, null);
    }

    public CdcLobSpiller(LobSpillStore store, long thresholdBytes, SyncConfig config) {
        this(store, thresholdBytes, config, null);
    }

    /**
     * @param cleansers table name -> compiled column rules; {@code null} compiles the rules
     *                  for every spiller, which is what hand-built call sites keep doing
     */
    public CdcLobSpiller(LobSpillStore store, long thresholdBytes, SyncConfig config,
                         Function<String, ColumnCleanser> cleansers) {
        this.store = store;
        this.thresholdBytes = Math.max(0L, thresholdBytes);
        this.config = config;
        this.cleansers = cleansers;
    }

    public boolean enabled() {
        return store != null && thresholdBytes > 0;
    }

    /**
     * Rewrites oversized text/binary values in place and returns the same map. Ordinary
     * values and already-spilled handles are left untouched.
     */
    public Map<String, Object> spill(CdcTableBinding binding, Map<String, Object> row) {
        if (!enabled() || row == null) {
            return row;
        }
        Set<String> keepInline = forceInline(binding);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof LobValue || keepInline.contains(key(entry.getKey()))) {
                continue;
            }
            if (value instanceof String) {
                String text = (String) value;
                if (LobBinder.utf8ByteLength(text) > thresholdBytes) {
                    entry.setValue(spillText(text));
                }
            } else if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                if (bytes.length > thresholdBytes) {
                    entry.setValue(spillBinary(bytes));
                }
            }
        }
        return row;
    }

    private LobValue spillText(String text) {
        try {
            return session().spillText(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot spill a CDC text value to "
                    + "sync.lob.spill-dir", e);
        }
    }

    private LobValue spillBinary(byte[] bytes) {
        try {
            return session().spillBinary(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot spill a CDC binary value to "
                    + "sync.lob.spill-dir", e);
        }
    }

    private LobSpillSession session() {
        if (closed) {
            throw new IllegalStateException("CDC LOB spiller is already closed");
        }
        if (session == null) {
            session = store.open();
        }
        return session;
    }

    private Set<String> forceInline(CdcTableBinding binding) {
        return forceInline.computeIfAbsent(binding, b -> {
            Set<String> names = new HashSet<>();
            for (String pk : b.pkNames()) {
                names.add(key(pk));
            }
            ColumnCleanser cleanser = cleansers == null
                    ? ColumnCleanser.forTable(config, b.getTable())
                    : cleansers.apply(b.getTable());
            for (String column : cleanser.columns()) {
                names.add(key(column));
            }
            return names;
        });
    }

    private static String key(String column) {
        return column == null ? "" : column.toUpperCase(Locale.ROOT);
    }

    /** Releases every value this transaction spilled; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
