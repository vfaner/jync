package com.qqmu.jync.service.cdc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.cdc.YStreamEvent.Kind;
import com.qqmu.jync.service.cdc.YStreamEvent.Operation;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.sics.ystream.YstreamClientBoot;
import com.sics.ystream.exception.YstreamSqlException;
import com.sics.ystream.metadata.TableId;
import com.sics.ystream.result.DmlType;
import com.sics.ystream.result.LcrType;
import com.sics.ystream.result.XactType;
import com.sics.ystream.result.YstreamChunkInterface;
import com.sics.ystream.result.YstreamColumn;
import com.sics.ystream.result.YstreamColumns;
import com.sics.ystream.result.YstreamDdlInterface;
import com.sics.ystream.result.YstreamDmlInterface;
import com.sics.ystream.result.YstreamLcrInterface;
import com.sics.ystream.result.YstreamXactInterface;

import lombok.extern.slf4j.Slf4j;

/**
 * The real {@link YStreamClientAdapter}: wraps one open {@code YstreamClientBoot} and
 * translates its LCRs into {@link YStreamEvent}s.
 *
 * <p>{@code YstreamClientBoot.getClient()} builds a fresh client per call (verified in the
 * jar: it is a factory, not a singleton), so one consumer per project holds its own
 * connection — two YStream projects in one JVM do not share state.
 */
@Slf4j
final class YstreamLcrAdapter implements YStreamClientAdapter {

    private final YstreamClientBoot<YstreamLcrInterface> client;

    YstreamLcrAdapter(YstreamClientBoot<YstreamLcrInterface> client) {
        this.client = client;
    }

    @Override
    public YStreamEvent next() throws Exception {
        YstreamLcrInterface lcr = client.next();
        return lcr == null ? null : toEvent(lcr);
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            // Teardown must never mask the failure that made the consumer stop.
            log.debug("Closing the YStream client failed: {}", e.toString());
        }
    }

    /**
     * Translates one LCR. Package-private and static so the mapping is unit-testable on
     * its own (the consumer never sees the library's types).
     *
     * <p>A column value the library cannot decode is deterministic — the same LCR decodes
     * the same way on every replay — so it is reported as a blocked stream rather than a
     * transient read failure that would retry the same row forever.
     */
    static YStreamEvent toEvent(YstreamLcrInterface lcr) {
        if (lcr == null) {
            return null;
        }
        try {
            return translate(lcr);
        } catch (YstreamSqlException e) {
            throw YStreamFatalException.blocked(
                    "a column value of the stream could not be decoded", e);
        }
    }

    private static YStreamEvent translate(YstreamLcrInterface lcr) throws YstreamSqlException {
        YStreamPosition position = YStreamClientFactory.fromClientPosition(lcr.getPosition());
        LcrType type = lcr.getLcrType();
        if (type == null) {
            return new YStreamEvent(Kind.IGNORED, null, null, null, null, null, false, position);
        }
        switch (type) {
            case YSTREAM_XACT:
                return YStreamEvent.xact(xactKind(((YstreamXactInterface) lcr).getXactType()),
                        position);
            case YSTREAM_DML:
                return dmlEvent((YstreamDmlInterface) lcr, position);
            case YSTREAM_DDL: {
                YstreamDdlInterface ddl = (YstreamDdlInterface) lcr;
                TableId table = ddl.getTableId();
                return YStreamEvent.ddl(table == null ? null : table.getSchema(),
                        table == null ? null : table.getTable(), position);
            }
            case YSTREAM_CHUNK: {
                TableId table = ((YstreamChunkInterface) lcr).getTableId();
                return YStreamEvent.chunk(table == null ? null : table.getSchema(),
                        table == null ? null : table.getTable(), position);
            }
            default:
                // YSTREAM_METADATA / YSTREAM_SEQ_HWM: table metadata rides along with each
                // DML (the LCR names its own table), and sequence high-water marks have no
                // row to apply.
                return new YStreamEvent(Kind.IGNORED, null, null, null, null, null, false,
                        position);
        }
    }

    private static YStreamEvent dmlEvent(YstreamDmlInterface dml, YStreamPosition position)
            throws YstreamSqlException {
        TableId table = dml.getTableId();
        String schema = table == null ? null : table.getSchema();
        String name = table == null ? null : table.getTable();
        DmlType dmlType = dml.getDmlType();
        if (dmlType == DmlType.CHUNK) {
            return YStreamEvent.chunk(schema, name, position);
        }
        if (dmlType == null) {
            return new YStreamEvent(Kind.IGNORED, null, schema, name, null, null, false,
                    position);
        }
        Operation operation = switch (dmlType) {
            case INSERT -> Operation.INSERT;
            case UPDATE -> Operation.UPDATE;
            case DELETE -> Operation.DELETE;
            default -> null;
        };
        return new YStreamEvent(Kind.DML, operation, schema, name,
                values(dml.getNewValues()), values(dml.getOldValues()),
                dml.hasChunkData(), position);
    }

    private static Kind xactKind(XactType type) {
        if (type == null) {
            return Kind.IGNORED;
        }
        return switch (type) {
            case BEGIN -> Kind.XACT_BEGIN;
            case COMMIT -> Kind.XACT_COMMIT;
            case SAVEPOINT -> Kind.XACT_SAVEPOINT;
            case ROLLBACK_TO_SAVEPOINT -> Kind.XACT_ROLLBACK_TO_SAVEPOINT;
        };
    }

    /**
     * Column name → value. A NULL column is kept (with a null value): dropping it would
     * make the row's column set disagree with the target table and silently skip the
     * column on upsert. Deleted columns ({@code getColumnsWithoutDeleted}) are the ones the
     * source's current definition no longer has; they cannot be bound anyway.
     */
    private static Map<String, Object> values(YstreamColumns columns)
            throws YstreamSqlException {
        if (columns == null) {
            return new LinkedHashMap<>();
        }
        List<YstreamColumn> present = columns.getColumnsWithoutDeleted();
        if (present == null || present.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (YstreamColumn column : present) {
            if (column == null || column.getColumn() == null) {
                continue;
            }
            values.put(column.getColumn().getColumnName(),
                    column.isNull() ? null : column.getData());
        }
        return values;
    }
}
