package com.qqmu.jync.service.cdc;

import java.util.Map;

import com.qqmu.jync.config.SyncProperties;

/**
 * Bounds the memory one source transaction may buffer before Jync refuses it.
 *
 * <p>Every CDC engine applies one source transaction as one target commit, so its rows have
 * to sit in memory until the commit event arrives. A bulk {@code UPDATE}/{@code DELETE} or a
 * one-off data repair can therefore buffer millions of rows, exhaust the heap, and — because
 * {@code OutOfMemoryError} is caught by the handler's choke path and classified as
 * transient — reconnect and replay the very same transaction until the project parks in
 * ERROR. The cap turns that death spiral into one clear, fatal verdict a human can act on.
 *
 * <p>Counters are monotonic over the transaction: rows folded away by same-PK last-op-wins
 * are still counted. The cap is a safety valve, not an accounting figure — a transaction
 * that streamed more than the limit through one commit is the pathological case either way.
 * A limit of {@code 0} disables that dimension.
 */
final class CdcTransactionBudget {

    /**
     * Message fragment {@link CdcFailureClassifier} matches to park the project instead of
     * reconnecting: reconnecting replays the same transaction and fails the same way.
     */
    static final String TOO_LARGE_MARKER = "CDC transaction exceeds the configured buffer limit";

    private final long maxRows;
    private final long maxBytes;
    private long rows;
    private long bytes;

    CdcTransactionBudget(long maxRows, long maxBytes) {
        this.maxRows = Math.max(0L, maxRows);
        this.maxBytes = Math.max(0L, maxBytes);
    }

    /** The limits configured under {@code sync.cdc.*}. */
    static CdcTransactionBudget from(SyncProperties properties) {
        return new CdcTransactionBudget(properties.getCdc().getMaxTransactionRows(),
                properties.getCdc().getMaxTransactionBytes());
    }

    /** A budget that never trips; used by hand-built handlers in tests. */
    static CdcTransactionBudget unlimited() {
        return new CdcTransactionBudget(0L, 0L);
    }

    /**
     * Counts one buffered row and throws {@link CdcTransactionTooLargeException} once the
     * transaction crosses either limit.
     */
    void add(Map<String, Object> row) {
        if (maxRows == 0 && maxBytes == 0) {
            return;
        }
        rows++;
        bytes += estimateBytes(row);
        if ((maxRows > 0 && rows > maxRows) || (maxBytes > 0 && bytes > maxBytes)) {
            throw new CdcTransactionTooLargeException(rows, bytes, maxRows, maxBytes);
        }
    }

    /** Starts accounting for the next source transaction. */
    void reset() {
        rows = 0;
        bytes = 0;
    }

    /**
     * Rough heap footprint of one mapped row. Values above the spill threshold were already
     * replaced by a small replay handle, so only in-memory payloads need estimating.
     */
    static long estimateBytes(Map<String, Object> row) {
        long size = 16L; // map entry overhead
        for (Object value : row.values()) {
            if (value instanceof CharSequence text) {
                size += 2L * text.length();
            } else if (value instanceof byte[] binary) {
                size += binary.length;
            } else {
                size += 32L;
            }
        }
        return size;
    }

    /** Fatal, non-transient: retrying replays the same transaction and cannot succeed. */
    static final class CdcTransactionTooLargeException extends IllegalStateException {
        CdcTransactionTooLargeException(long rows, long bytes, long maxRows, long maxBytes) {
            super(TOO_LARGE_MARKER + ": " + rows + " row(s), about " + bytes
                    + " byte(s) buffered for one source transaction (limits: "
                    + describe(maxRows, "rows") + ", " + describe(maxBytes, "bytes")
                    + "). Apply the source transaction in smaller batches, raise"
                    + " sync.cdc.max-transaction-rows / sync.cdc.max-transaction-bytes,"
                    + " or switch this project to polling capture.");
        }

        private static String describe(long limit, String unit) {
            return limit == 0 ? "unlimited " + unit : limit + " " + unit;
        }
    }
}
