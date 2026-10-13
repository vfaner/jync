package com.qqmu.jync.util;

import java.util.Collection;

/** Small collection helpers shared across the sync/CDC layers (B3). */
public final class CollectionUtil {

    private CollectionUtil() { }

    /**
     * Case-insensitive membership test over any collection of strings (B3: the single
     * home of the helper formerly duplicated — in three non-identical shapes — in
     * CdcEventApplier, SyncConfig and DataSyncService).
     *
     * <p>Null-safe on all three inputs: a null {@code haystack} or null {@code needle}
     * is "not contained" rather than an error, and null elements are skipped instead of
     * throwing (the two former List-based copies NPE'd on a null element).
     * {@code needle.equalsIgnoreCase(candidate)} also puts the known-non-null side
     * first, which is what makes the element-null safety fall out for free.
     */
    public static boolean containsIgnoreCase(Collection<String> haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        for (String candidate : haystack) {
            if (needle.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}
