package com.qqmu.jync.dto;

import java.util.List;

/**
 * Result of the quick content check shown in the preview modal: the first rows of the two
 * previews, compared by position, plus the source/target column counts.
 *
 * <p>This is deliberately a glance, not the full content audit: it compares only what the
 * preview already displays, so it never opens another query shape and never reports values
 * back to the browser. The scheduled audit remains the authority for drift.
 */
public record TablePreviewCheck(
        String table,
        Status status,
        int sourceColumns,
        int targetColumns,
        boolean columnCountMatch,
        int sampledRows,
        int matchedRows,
        int mismatchedRows,
        int missingRows,
        List<String> mismatchColumns,
        String detail,
        long elapsedMs) {

    public enum Status { OK, MISMATCH, SKIPPED, ERROR }

    public static TablePreviewCheck error(String table, String detail, long elapsedMs) {
        return new TablePreviewCheck(table, Status.ERROR, 0, 0, false, 0, 0, 0, 0,
                List.of(), detail, elapsedMs);
    }
}
