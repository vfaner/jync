package com.qqmu.jync.service.lob;

/**
 * Tracks the approximate heap held by the rows currently in a JDBC batch.
 *
 * <p>Row-count batching is blind to size: ten thousand 10-byte rows and ten 8 MB rows both
 * look like "a batch". The writer flushes when adding the next row would cross the budget
 * (a single row larger than the budget is allowed to occupy a batch by itself — refusing
 * it would deadlock the load).
 */
public final class BatchByteBudget {

    private final long maxBytes;
    private long used;
    private int rows;

    public BatchByteBudget(long maxBytes) {
        this.maxBytes = Math.max(0L, maxBytes);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long used() {
        return used;
    }

    public int rows() {
        return rows;
    }

    public boolean isEmpty() {
        return rows == 0;
    }

    /** Whether adding a row of this size should first flush the current batch. */
    public boolean wouldExceed(long bytes) {
        return rows > 0 && maxBytes > 0 && used + Math.max(0L, bytes) > maxBytes;
    }

    public void add(long bytes) {
        used += Math.max(0L, bytes);
        rows++;
    }

    public void reset() {
        used = 0;
        rows = 0;
    }

    /** Rough heap estimate of one value; enough to decide a flush, not an accounting fact. */
    public static long sizeOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof LobValue) {
            long length = ((LobValue) value).length();
            return length > 0 ? length : 0L;
        }
        if (value instanceof CharSequence) {
            // Allocation-free: a per-row getBytes() would add a full copy of every string
            // just to decide when to flush.
            return LobBinder.utf8ByteLength((CharSequence) value);
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).length;
        }
        return 8L;
    }
}
