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
 */
public record YStreamEvent(Kind kind, Operation operation, String schema, String table,
                           Map<String, Object> newValues, Map<String, Object> oldValues,
                           boolean chunked, YStreamPosition position) {

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
        return xact(Kind.XACT_SAVEPOINT, position);
    }

    public static YStreamEvent rollbackToSavepoint(YStreamPosition position) {
        return xact(Kind.XACT_ROLLBACK_TO_SAVEPOINT, position);
    }

    public static YStreamEvent row(Operation operation, String schema, String table,
                                   Map<String, Object> newValues, Map<String, Object> oldValues,
                                   YStreamPosition position) {
        return new YStreamEvent(Kind.DML, operation, schema, table, newValues, oldValues,
                false, position);
    }

    public static YStreamEvent chunk(String schema, String table, YStreamPosition position) {
        return new YStreamEvent(Kind.CHUNK, null, schema, table, null, null, true, position);
    }

    public static YStreamEvent ddl(String schema, String table, YStreamPosition position) {
        return new YStreamEvent(Kind.DDL, null, schema, table, null, null, false, position);
    }
}
