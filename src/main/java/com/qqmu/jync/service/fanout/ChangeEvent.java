package com.qqmu.jync.service.fanout;

import java.util.Map;

/**
 * One normalized CDC event, independent of the source engine's wire format.
 *
 * <p>Row events carry the transaction id and are buffered by the target worker until the
 * matching {@link EventKind#COMMIT}; a DDL event is applied on its own (transactionId is
 * null). Payload maps never contain LOB blobs inline in the production path — the capture
 * side spills oversized values and carries a reference instead — but the PoC keeps the
 * shape simple.
 */
public record ChangeEvent(
        EventKind kind,
        String position,
        String transactionId,
        String schema,
        String table,
        ChangeOperation operation,
        Map<String, Object> row,
        Map<String, Object> oldRow,
        String ddlSql,
        long sourceTimeMillis) {

    public enum EventKind { ROW, COMMIT, DDL }

    public enum ChangeOperation { INSERT, UPDATE, UPSERT, DELETE }

    public static ChangeEvent row(String position, String transactionId, String schema,
                                  String table, ChangeOperation operation,
                                  Map<String, Object> row, Map<String, Object> oldRow,
                                  long sourceTimeMillis) {
        return new ChangeEvent(EventKind.ROW, position, transactionId, schema, table,
                operation, row, oldRow, null, sourceTimeMillis);
    }

    public static ChangeEvent commit(String position, String transactionId,
                                     long sourceTimeMillis) {
        return new ChangeEvent(EventKind.COMMIT, position, transactionId, null, null,
                null, null, null, null, sourceTimeMillis);
    }

    public static ChangeEvent ddl(String position, String schema, String table, String sql,
                                  long sourceTimeMillis) {
        return new ChangeEvent(EventKind.DDL, position, null, schema, table, null,
                null, null, sql, sourceTimeMillis);
    }
}
