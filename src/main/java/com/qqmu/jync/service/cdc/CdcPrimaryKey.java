package com.qqmu.jync.service.cdc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Primary-key identity for row folding: which buffered change a row collides with, and
 * whether an UPDATE actually moved its key.
 *
 * <p>{@code byte[]} is the one mapped value type without value equality — MySQL
 * BINARY/VARBINARY/BLOB columns and SQL Server binary/varbinary arrive as a fresh array
 * per row image, so {@code Objects.equals} (and {@code List.equals} over a PK tuple)
 * compares identities and answers "different" for two images of the SAME key. That
 * misreads every ordinary UPDATE on a binary-PK table as a key move — the folded buffer
 * then holds an upsert of the after-image plus a delete of the old key, and the applier's
 * upsert-before-delete order deletes the row it just wrote — and it defeats same-key
 * folding for DELETE+INSERT (REPLACE). {@link ByteBuffer#wrap(byte[])} compares its
 * remaining bytes, giving the key the value semantics the rest of the maps already have.
 */
final class CdcPrimaryKey {

    private CdcPrimaryKey() {
    }

    /** The row's PK tuple, canonicalized so binary primary keys compare by content. */
    static List<Object> of(Map<String, Object> row, CdcTableBinding binding) {
        List<Object> key = new ArrayList<>(binding.pkNames().size());
        for (String pk : binding.pkNames()) {
            key.add(comparable(row.get(pk)));
        }
        return key;
    }

    /** True when any PK column's value differs between the before- and after-images. */
    static boolean changed(Map<String, Object> before, Map<String, Object> after,
                           CdcTableBinding binding) {
        for (String pk : binding.pkNames()) {
            if (!Objects.equals(comparable(before.get(pk)), comparable(after.get(pk)))) {
                return true;
            }
        }
        return false;
    }

    private static Object comparable(Object value) {
        return value instanceof byte[] ? ByteBuffer.wrap((byte[]) value) : value;
    }
}
