package com.qqmu.jync.service.cdc;

import java.util.Map;

import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;

/**
 * One YStream LCR, translated out of the client library's types.
 *
 * <p>Everything downstream — the consumer's transaction buffer, its tests — works on this
 * record, so the library's interfaces are read in exactly one place
 * ({@link YstreamLcrAdapter}) and a fake {@link YStreamClientAdapter} can replay any
 * stream shape without a live source.
 *
 * @param kind       what the LCR is
 * @param operation  INSERT/UPDATE/DELETE for {@link Kind#DML}, else null
 * @param schema     owning schema for DML/DDL, else null
 * @param table      table name for DML/DDL, else null
 * @param newValues  after-image of a DML (column name → value; a NULL column is present
 *                   with a null value), else null
 * @param oldValues  before-image as far as the stream carries it — under ALL-column
 *                   supplemental logging a DELETE arrives as a full mirror
 * @param chunked    the row carries chunked (out-of-line) LOB data this consumer cannot
 *                   assemble
 * @param position   the client position of this LCR; null when the source did not send one
 * @param sourceTimeMillis wall-clock time the source wrote this LCR (from the DML's SCN), or
 *                   0 when the LCR carries none — transaction boundaries and DDLs do not.
 *                   This is the only honest source for the lag gauge: the time the row
 *                   reaches us is not how far behind the source is.
 * @param savepointSsn SAVEPOINT / ROLLBACK_TO_SAVEPOINT 的 SSN（其余 LCR 为 0）。回滚按此
 *                  匹配具体 savepoint 而非恒取最近一个，否则回滚到非最近 savepoint 会复活
 *                  已回滚的行。
 */
public record YStreamEvent(Kind kind, Operation operation, String schema, String table,
                           Map<String, Object> newValues, Map<String, Object> oldValues,
                           boolean chunked, YStreamPosition position, long sourceTimeMillis,
                           long savepointSsn) {

    /** Compatibility form for LCRs that carry no source time. */
    public YStreamEvent(Kind kind, Operation operation, String schema, String table,
                        Map<String, Object> newValues, Map<String, Object> oldValues,
                        boolean chunked, YStreamPosition position) {
        this(kind, operation, schema, table, newValues, oldValues, chunked, position, 0L, 0L);
    }

    /** Compatibility form for LCRs that carry a source time but no savepoint SSN. */
    public YStreamEvent(Kind kind, Operation operation, String schema, String table,
                        Map<String, Object> newValues, Map<String, Object> oldValues,
                        boolean chunked, YStreamPosition position, long sourceTimeMillis) {
        this(kind, operation, schema, table, newValues, oldValues, chunked, position,
                sourceTimeMillis, 0L);
    }

    /** What a YStream LCR is; the client's {@code LcrType} plus the xact subtypes. */
    public enum Kind {
        XACT_BEGIN, XACT_COMMIT, XACT_SAVEPOINT, XACT_ROLLBACK_TO_SAVEPOINT,
        DML, DDL, CHUNK,
        /** Metadata / sequence high-water-mark LCRs: nothing to apply. */
        IGNORED
    }

    public enum Operation { INSERT, UPDATE, DELETE }

    static YStreamEvent xact(Kind kind, YStreamPosition position) {
        return new YStreamEvent(kind, null, null, null, null, null, false, position);
    }

    public static YStreamEvent begin(YStreamPosition position) {
        return xact(Kind.XACT_BEGIN, position);
    }

    public static YStreamEvent commit(YStreamPosition position) {
        return xact(Kind.XACT_COMMIT, position);
    }

    public static YStreamEvent savepoint(YStreamPosition position) {
        return savepoint(0L, position);
    }

    public static YStreamEvent savepoint(long ssn, YStreamPosition position) {
        return new YStreamEvent(Kind.XACT_SAVEPOINT, null, null, null, null, null, false,
                position, 0L, ssn);
    }

    public static YStreamEvent rollbackToSavepoint(YStreamPosition position) {
        return rollbackToSavepoint(0L, position);
    }

    public static YStreamEvent rollbackToSavepoint(long ssn, YStreamPosition position) {
        return new YStreamEvent(Kind.XACT_ROLLBACK_TO_SAVEPOINT, null, null, null, null, null,
                false, position, 0L, ssn);
    }

    public static YStreamEvent row(Operation operation, String schema, String table,
                                   Map<String, Object> newValues, Map<String, Object> oldValues,
                                   YStreamPosition position) {
        return row(operation, schema, table, newValues, oldValues, position, 0L);
    }

    public static YStreamEvent row(Operation operation, String schema, String table,
                                   Map<String, Object> newValues, Map<String, Object> oldValues,
                                   YStreamPosition position, long sourceTimeMillis) {
        return new YStreamEvent(Kind.DML, operation, schema, table, newValues, oldValues,
                false, position, sourceTimeMillis);
    }

    public static YStreamEvent chunk(String schema, String table, YStreamPosition position) {
        return new YStreamEvent(Kind.CHUNK, null, schema, table, null, null, true, position);
    }

    public static YStreamEvent ddl(String schema, String table, YStreamPosition position) {
        return new YStreamEvent(Kind.DDL, null, schema, table, null, null, false, position);
    }
}
