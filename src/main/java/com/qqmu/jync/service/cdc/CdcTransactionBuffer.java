package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One source transaction's buffered changes, shared by the Oracle and PostgreSQL engines.
 *
 * <p>Rows are folded by primary key as they arrive: a row's net effect inside the
 * transaction is its LAST image, and it is either an upsert or a delete, never both. The
 * buffers are keyed maps rather than scanned lists, so folding a transaction that touches
 * every key twice — a bulk {@code REPLACE}/{@code MERGE}, a data repair — stays linear; the
 * list version was O(n²) on exactly that shape.
 *
 * <p>Iteration order is the order of each key's last occurrence. Upserts are applied before
 * deletes (the applier's contract), which is what makes PK chains (1→2→3 in one
 * transaction) land on the source's final state.
 */
final class CdcTransactionBuffer {

    private final Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> upserts =
            new LinkedHashMap<>();
    private final Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> deletes =
            new LinkedHashMap<>();

    void upsert(CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, binding);
        drop(deletes, binding, key);
        // remove-then-put: the last image wins and moves to the end (source order), and the
        // superseded copy is not kept.
        Map<List<Object>, Map<String, Object>> buffered =
                upserts.computeIfAbsent(binding, k -> new LinkedHashMap<>());
        buffered.remove(key);
        buffered.put(key, row);
    }

    void delete(CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, binding);
        drop(upserts, binding, key);
        Map<List<Object>, Map<String, Object>> buffered =
                deletes.computeIfAbsent(binding, k -> new LinkedHashMap<>());
        buffered.remove(key);
        buffered.put(key, row);
    }

    /**
     * Moves this transaction's buffered DELETES from a table's stale binding to its refreshed
     * one — PostgreSQL's mid-transaction metadata refresh.
     *
     * <p>Bindings are keyed by object identity, and a refresh builds a new one; without this
     * a transaction that deletes a key before the refresh and re-inserts it after would hold
     * the same primary key in two buckets. The fold then never sees them as one key, and
     * because upserts are applied before deletes the stale delete runs last: the row the
     * source still has disappears from the target, silently.
     *
     * <p>Only deletes move. They bind primary keys alone, so the refreshed column list
     * changes nothing about how they are applied, while the upserts already buffered were
     * built against the old column set and are left as they were. A destination entry for the
     * same key always post-dates the refresh, so it wins and the moved copy is dropped.
     */
    void rebindDeletes(CdcTableBinding from, CdcTableBinding to) {
        if (from == null || to == null || from == to) {
            return;
        }
        Map<List<Object>, Map<String, Object>> moved = deletes.get(from);
        if (moved == null || moved.isEmpty()) {
            return;
        }
        deletes.remove(from);
        Map<List<Object>, Map<String, Object>> destination =
                deletes.computeIfAbsent(to, k -> new LinkedHashMap<>());
        Map<List<Object>, Map<String, Object>> destinationUpserts = upserts.get(to);
        for (Map.Entry<List<Object>, Map<String, Object>> entry : moved.entrySet()) {
            if (destination.containsKey(entry.getKey())
                    || (destinationUpserts != null
                            && destinationUpserts.containsKey(entry.getKey()))) {
                continue;   // a later image under the new binding already settled this key
            }
            destination.put(entry.getKey(), entry.getValue());
        }
        if (destination.isEmpty()) {
            deletes.remove(to);
        }
    }

    /** Removes one key, dropping the binding's map once it holds nothing — {@link #isEmpty}
     * and {@link #upserts()}/{@link #deletes()} must not report a table the transaction no
     * longer touches. */
    private static void drop(
            Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> direction,
            CdcTableBinding binding, List<Object> key) {
        Map<List<Object>, Map<String, Object>> buffered = direction.get(binding);
        if (buffered == null) {
            return;
        }
        buffered.remove(key);
        if (buffered.isEmpty()) {
            direction.remove(binding);
        }
    }

    boolean isEmpty() {
        return upserts.isEmpty() && deletes.isEmpty();
    }

    /** Net buffered rows in both directions, for diagnostics. */
    int size() {
        int size = 0;
        for (Map<List<Object>, Map<String, Object>> rows : upserts.values()) {
            size += rows.size();
        }
        for (Map<List<Object>, Map<String, Object>> rows : deletes.values()) {
            size += rows.size();
        }
        return size;
    }

    void clear() {
        upserts.clear();
        deletes.clear();
    }

    /**
     * An independent copy of this buffer — the YStream engine's SAVEPOINT snapshot.
     *
     * <p>Oracle and PostgreSQL never need one: their streams carry no savepoints. YStream
     * does, and a folded buffer cannot "unfold" the image it overwrote, so the only honest
     * {@code ROLLBACK TO SAVEPOINT} is restoring a copy taken at that savepoint. Row maps
     * are treated as immutable once buffered (every producer builds a fresh map per row),
     * so the copy duplicates the maps and lists, never the rows.
     */
    CdcTransactionBuffer copy() {
        CdcTransactionBuffer copy = new CdcTransactionBuffer();
        copyInto(upserts, copy.upserts);
        copyInto(deletes, copy.deletes);
        return copy;
    }

    /** Replaces this buffer's contents with {@code snapshot}'s (which stays untouched). */
    void restoreFrom(CdcTransactionBuffer snapshot) {
        clear();
        copyInto(snapshot.upserts, upserts);
        copyInto(snapshot.deletes, deletes);
    }

    private static void copyInto(
            Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> source,
            Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> destination) {
        source.forEach((binding, rows) ->
                destination.put(binding, new LinkedHashMap<>(rows)));
    }

    /** Net upserts per binding, ready to apply; a fresh map with fresh lists per call. */
    Map<CdcTableBinding, List<Map<String, Object>>> upserts() {
        return materialize(upserts);
    }

    /** Net deletes per binding, ready to apply; a fresh map with fresh lists per call. */
    Map<CdcTableBinding, List<Map<String, Object>>> deletes() {
        return materialize(deletes);
    }

    private static Map<CdcTableBinding, List<Map<String, Object>>> materialize(
            Map<CdcTableBinding, Map<List<Object>, Map<String, Object>>> source) {
        Map<CdcTableBinding, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        source.forEach((binding, rows) -> copy.put(binding, new ArrayList<>(rows.values())));
        return copy;
    }
}
