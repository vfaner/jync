package com.qqmu.jync.service.cdc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * One source transaction's rows, as YStream delivers them.
 *
 * <p>YStream reports transaction boundaries explicitly ({@code YSTREAM_XACT} BEGIN / COMMIT /
 * SAVEPOINT / ROLLBACK_TO_SAVEPOINT), so the assembler is a fold plus a savepoint stack:
 * rows before a savepoint survive {@code ROLLBACK TO}, rows after it do not.
 *
 * <p>The savepoint snapshot is the folded buffer, not a row index. Folding (last write wins
 * per primary key) has already forgotten the row a later image replaced, so restoring "the
 * rows since the savepoint" is impossible; a copy of the folded state is the only honest
 * restore point. Rolling back keeps the savepoint (SQL semantics) — the same savepoint may
 * be rolled back to repeatedly, and each rollback restores the same snapshot.
 */
final class YStreamTransactionAssembler {

    private final CdcTransactionBuffer buffer = new CdcTransactionBuffer();
    private final Deque<CdcTransactionBuffer> savepoints = new ArrayDeque<>();

    /**
     * Starts a new transaction.
     *
     * @return {@code true} when the previous transaction still held rows — a source that
     *         rolled its whole transaction back leaves exactly this state, and the rows
     *         belong to a transaction that will never commit
     */
    boolean begin() {
        boolean discarded = !buffer.isEmpty();
        reset();
        return discarded;
    }

    void upsert(CdcTableBinding binding, Map<String, Object> row) {
        buffer.upsert(binding, row);
    }

    void delete(CdcTableBinding binding, Map<String, Object> row) {
        buffer.delete(binding, row);
    }

    /**
     * Pushes a savepoint holding the current folded state.
     *
     * @return how many entries the snapshot copied — the caller charges that to the
     *         transaction budget, because the copies live on the heap until the commit
     */
    int savepoint() {
        int heldRows = buffer.size();
        savepoints.push(buffer.copy());
        return heldRows;
    }

    /** Restores the innermost savepoint; a stray rollback (no savepoint) is a no-op. */
    void rollbackToSavepoint() {
        CdcTransactionBuffer snapshot = savepoints.peek();
        if (snapshot != null) {
            buffer.restoreFrom(snapshot);
        }
    }

    boolean isEmpty() {
        return buffer.isEmpty();
    }

    /** The transaction's rows, leaving the assembler ready for the next BEGIN. */
    CdcTransactionBuffer take() {
        CdcTransactionBuffer taken = buffer.copy();
        reset();
        return taken;
    }

    /** Drops everything buffered, savepoints included — a savepoint may never cross a commit. */
    void reset() {
        buffer.clear();
        savepoints.clear();
    }
}
