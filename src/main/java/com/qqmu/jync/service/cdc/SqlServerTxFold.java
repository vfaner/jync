package com.qqmu.jync.service.cdc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import lombok.extern.slf4j.Slf4j;

/**
 * P0-6: order-preserving fold of one drain's SQL Server CDC row events.
 *
 * <p>Rows must be accepted in source order — per capture instance that is
 * {@code ORDER BY __$start_lsn, __$seqval} (the drain SQL guarantees it). Within one
 * transaction (one {@code __$start_lsn}) the fold keeps, per binding, the NET effect
 * of everything that happened, using the same semantics as the MySQL handler's FIX C:
 *
 * <ul>
 *   <li>op=1 (delete) → the row's PK leaves the upsert list and enters the delete
 *       list: DELETE-then-INSERT of one PK ends as an upsert, not as a lost row;</li>
 *   <li>op=2 (insert) / op=4 (update-after) → the PK leaves the delete list and
 *       enters the upsert list;</li>
 *   <li>op=3 (update-before) → parked as this binding's before-image; the paired
 *       op=4 compares PKs and, when the PK moved, adds a delete of the OLD key —
 *       without it an UPDATE that changes the PK leaves a ghost row on the target.</li>
 * </ul>
 *
 * <p>Applying upserts before deletes per transaction makes even PK chains
 * (1→2→3 within one tx) land on the source's final state: upsert(3), delete(1),
 * delete(2) — a row 2 that pre-existed on the target is removed, exactly like the
 * source's UPDATE took it away.
 *
 * <p>Transactions iterate in txLsn ascending order (a {@code 0x}-prefixed fixed-width
 * lowercase hex string from {@code CONVERT(varchar, lsn, 1)} — lexicographic order IS
 * LSN order), so flushing in iteration order keeps {@code advanceLsn} monotonic even
 * when several capture instances contributed to one drain.
 */
@Slf4j
final class SqlServerTxFold {

    /** One binding's folded net changes inside one transaction. */
    static final class Buffer {
        final List<Map<String, Object>> upserts = new ArrayList<>();
        final List<Map<String, Object>> deletes = new ArrayList<>();

        boolean isEmpty() {
            return upserts.isEmpty() && deletes.isEmpty();
        }
    }

    /** txLsn (ascending) -> binding -> folded buffer. */
    private final TreeMap<String, Map<CdcTableBinding, Buffer>> byTx = new TreeMap<>();
    /** binding -> last op=3 image awaiting its paired op=4. */
    private final Map<CdcTableBinding, Map<String, Object>> pendingBefore = new LinkedHashMap<>();

    void accept(String txLsn, CdcTableBinding binding, int op, Map<String, Object> row) {
        switch (op) {
            case 3:
                // Update-before: never applied on its own; the paired op=4 decides
                // whether the old PK needs a delete.
                pendingBefore.put(binding, row);
                return;
            case 4: {
                Map<String, Object> before = pendingBefore.remove(binding);
                Buffer buf = buffer(txLsn, binding);
                appendUpsert(buf, binding, row);
                if (before != null && primaryKeyChanged(before, row, binding)) {
                    appendDelete(buf, binding, before);
                }
                return;
            }
            case 1:
                appendDelete(buffer(txLsn, binding), binding, row);
                return;
            case 2:
                appendUpsert(buffer(txLsn, binding), binding, row);
                return;
            default:
                log.warn("Ignoring SQL Server CDC row with unknown __$operation {}"
                        + " on {}.{}", op, binding.getDatabase(), binding.getTable());
        }
    }

    /** Transactions in txLsn ascending order; values are the per-binding buffers. */
    Map<String, Map<CdcTableBinding, Buffer>> ordered() {
        return byTx;
    }

    private Buffer buffer(String txLsn, CdcTableBinding binding) {
        return byTx.computeIfAbsent(txLsn, k -> new LinkedHashMap<>())
                .computeIfAbsent(binding, k -> new Buffer());
    }

    private static void appendUpsert(Buffer buf, CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = pkKey(row, binding);
        buf.deletes.removeIf(buffered -> pkKey(buffered, binding).equals(key));
        buf.upserts.add(row);
    }

    private static void appendDelete(Buffer buf, CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = pkKey(row, binding);
        buf.upserts.removeIf(buffered -> pkKey(buffered, binding).equals(key));
        buf.deletes.add(row);
    }

    private static boolean primaryKeyChanged(Map<String, Object> before, Map<String, Object> after,
                                             CdcTableBinding binding) {
        for (String pk : binding.pkNames()) {
            if (!Objects.equals(before.get(pk), after.get(pk))) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> pkKey(Map<String, Object> row, CdcTableBinding binding) {
        List<Object> key = new ArrayList<>(binding.pkNames().size());
        for (String pk : binding.pkNames()) {
            key.add(row.get(pk));
        }
        return key;
    }
}
