package com.qqmu.jync.dto;

import java.util.List;

/**
 * Outcome of one table's content audit: a handful of sampled keys were read on both sides
 * and their projected values compared. Counts and column names only — never the values
 * themselves, which would put customer data in a change log or an alert payload.
 */
public record TableContentAudit(String table, Status status, int sampled, int matched,
        int mismatched, int missingOnTarget, int skippedColumns,
        List<String> mismatchColumns, String detail, long elapsedMs) {

    public enum Status {
        /** Every sampled row exists on both sides with the same comparable values. */
        OK,
        /** At least one sampled row differs or is missing on the target. */
        MISMATCH,
        /** The table is not eligible for the audit; {@link #detail()} says why. */
        SKIPPED,
        /** Reading or comparing raised an error; the sync itself is unaffected. */
        ERROR
    }

    public static TableContentAudit skipped(String table, String reason, long elapsedMs) {
        return new TableContentAudit(table, Status.SKIPPED, 0, 0, 0, 0, 0,
                List.of(), reason, elapsedMs);
    }

    public static TableContentAudit error(String table, String message, long elapsedMs) {
        return new TableContentAudit(table, Status.ERROR, 0, 0, 0, 0, 0,
                List.of(), message, elapsedMs);
    }
}
