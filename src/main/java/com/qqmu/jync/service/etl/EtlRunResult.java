package com.qqmu.jync.service.etl;

/**
 * Outcome of one ETL run. The counts are the rows the source query returned and the rows the
 * target accepted; on failure the counts describe what was committed before the failure.
 */
public record EtlRunResult(boolean success, long rowsRead, long rowsWritten,
                           Side failureSide, String errorMessage) {

    /** Which side of the copy is to blame, so the operator knows whose SQL/table to fix. */
    public enum Side {
        /** The extraction query itself failed or the source connection died. */
        SOURCE,
        /** The target refused a DDL/DML statement or the target connection died. */
        TARGET,
        /** The task configuration is unusable (missing range condition, bad resume key, …). */
        PROGRAM
    }

    public static EtlRunResult success(long rowsRead, long rowsWritten) {
        return new EtlRunResult(true, rowsRead, rowsWritten, null, null);
    }

    public static EtlRunResult failure(long rowsRead, long rowsWritten, Side side, String message) {
        return new EtlRunResult(false, rowsRead, rowsWritten, side, message);
    }
}
