package com.qqmu.jync.service.etl;

import java.util.List;

/**
 * Outcome of one ETL run. The counts are the rows the source query returned and the rows the
 * target accepted; on failure the counts describe what was committed before the failure.
 *
 * <p>{@code skippedColumns} lists the extraction's columns the existing target does not have.
 * They are not copied — the run writes the intersection — but it is still a success, so the
 * labels travel with the result instead of failing it.
 */
public record EtlRunResult(boolean success, long rowsRead, long rowsWritten,
                           Side failureSide, String errorMessage,
                           List<String> skippedColumns) {

    /** Which side of the copy is to blame, so the operator knows whose SQL/table to fix. */
    public enum Side {
        /** The extraction query itself failed or the source connection died. */
        SOURCE,
        /** The target refused a DDL/DML statement or the target connection died. */
        TARGET,
        /** The task configuration is unusable (missing range condition, bad resume key, …). */
        PROGRAM
    }

    public EtlRunResult {
        skippedColumns = skippedColumns == null ? List.of() : List.copyOf(skippedColumns);
    }

    public static EtlRunResult success(long rowsRead, long rowsWritten) {
        return success(rowsRead, rowsWritten, List.of());
    }

    public static EtlRunResult success(long rowsRead, long rowsWritten,
                                       List<String> skippedColumns) {
        return new EtlRunResult(true, rowsRead, rowsWritten, null, null, skippedColumns);
    }

    public static EtlRunResult failure(long rowsRead, long rowsWritten, Side side, String message) {
        return failure(rowsRead, rowsWritten, side, message, List.of());
    }

    public static EtlRunResult failure(long rowsRead, long rowsWritten, Side side, String message,
                                       List<String> skippedColumns) {
        return new EtlRunResult(false, rowsRead, rowsWritten, side, message, skippedColumns);
    }
}
