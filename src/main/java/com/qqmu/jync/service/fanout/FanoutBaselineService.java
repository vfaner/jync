package com.qqmu.jync.service.fanout;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.FanoutProgress;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.sync.SourceReadSession;
import com.qqmu.jync.service.sync.StructureSyncService;
import com.qqmu.jync.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/**
 * First load of a fan-out project into every target.
 *
 * <p>Each target gets its own {@link SyncContext} (its own dialect/schema); the source is read
 * once and the rows are published to the shared transport, so the baseline is not copied
 * N times. Target tables are created before rows are published; a table without a primary key
 * is skipped (CDC cannot address it and the applier would have no upsert key).
 *
 * <p>The caller records the shared source anchor before calling this method and starts the
 * target workers first, so the baseline rows and the CDC stream share one order.
 */
@Slf4j
public final class FanoutBaselineService {

    private static final int ROW_BATCH = 500;

    /**
     * Byte budget per published batch. A 500-row batch of 1 MB blobs used to materialize
     * hundreds of megabytes (plus their serialized copies) before anything left the heap;
     * flushing on bytes as well as rows keeps one batch bounded regardless of row size.
     */
    private static final long ROW_BATCH_BYTES = 8L * 1024 * 1024;

    private final StructureSyncService structureSync;

    public FanoutBaselineService(StructureSyncService structureSync) {
        this.structureSync = structureSync;
    }

    /** One table's baseline result; the caller marks progress after every target acked. */
    public record TableLoad(String table, long rows) {
    }

    /** One target's id + context; the context resolves that target's dialect and schema. */
    public record Target(Long targetDbId, SyncContext context) {
    }

    public List<TableLoad> baseline(SyncContext sourceCtx, Connection sourceConn,
                                    List<Target> targets, EventTransport transport)
            throws Exception {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<TableMeta> tables = selectedTables(sourceCtx, sourceConn);
        List<TableMeta> keyed = new ArrayList<>();
        for (TableMeta table : tables) {
            if (table == null || !table.hasPrimaryKey()) {
                log.warn("Fan-out baseline skips {}: no primary key (CDC cannot address it)",
                        table == null ? "?" : table.getName());
                continue;
            }
            keyed.add(table);
        }
        for (Target target : targets) {
            ensureTargetTables(target, keyed);
        }
        List<TableLoad> loads = new ArrayList<>();
        // A run token makes baseline events unique across generations; otherwise a second
        // baseline in the same consumer run reuses the first one's metrics-dedup keys and
        // its row counts never reach the change log.
        String baselineRun = java.util.UUID.randomUUID().toString();
        for (TableMeta table : keyed) {
            long rows = publishTable(sourceCtx, sourceConn, table, transport, baselineRun);
            loads.add(new TableLoad(table.getName(), rows));
            log.info("Fan-out baseline: {} queued {} row(s) for {} target(s)",
                    table.getName(), rows, targets.size());
        }
        return loads;
    }

    private List<TableMeta> selectedTables(SyncContext ctx, Connection conn) throws SQLException {
        Set<String> names = ctx.getConfig().getTables();
        if (names == null || names.isEmpty()) {
            names = new HashSet<>(ctx.getSourceReader()
                    .listTableNames(conn, ctx.getSourceSchema()));
        }
        List<TableMeta> tables = new ArrayList<>();
        for (String name : names) {
            TableMeta table = ctx.getSourceReader().readTable(conn, ctx.getSourceSchema(), name);
            if (table != null) {
                tables.add(table);
            }
        }
        return tables;
    }

    private void ensureTargetTables(Target target, List<TableMeta> tables) throws SQLException {
        SyncContext ctx = target.context();
        try (Connection conn = ctx.getConnectionProvider().open(ctx.getTargetConfig())) {
            Set<String> existing = new HashSet<>();
            for (String name : ctx.getTargetReader()
                    .listTableNames(conn, ctx.getTargetSchema())) {
                existing.add(name.toUpperCase(Locale.ROOT));
            }
            for (TableMeta table : tables) {
                String targetName = ctx.getConfig().targetTableName(table.getName());
                if (existing.contains(targetName.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                com.qqmu.jync.dto.ChangeEvent create = com.qqmu.jync.dto.ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                        table.getName(), "Fan-out baseline auto-create", table);
                StructureSyncService.ApplyOutcome outcome = structureSync.apply(conn, create, ctx);
                if (!outcome.applied) {
                    throw new SQLException("Fan-out baseline could not create target table "
                            + targetName + " on target " + target.targetDbId() + ": "
                            + (outcome.error != null ? outcome.error : outcome.detail));
                }
            }
        }
    }

    private long publishTable(SyncContext sourceCtx, Connection sourceConn, TableMeta table,
                              EventTransport transport, String baselineRun) throws Exception {
        String sql = "SELECT * FROM " + sourceCtx.getSourceDialect()
                .qualify(sourceCtx.getSourceSchema(), table.getName());
        long rows = 0;
        com.qqmu.jync.service.converter.SqlDialect dialect = sourceCtx.getSourceDialect();
        // A streamed read, exactly like the single-target full load: with autocommit on the
        // MySQL/PG drivers ignore fetchSize and buffer the whole table client-side, which a
        // multi-million-row table turns into an OOM when batches are only bounded by bytes.
        boolean needsTransaction = dialect != null
                && dialect.requiresTransactionalSourceForStreaming();
        try (PreparedStatement ps = sourceConn.prepareStatement(sql);
             SourceReadSession session = SourceReadSession.begin(sourceConn, needsTransaction)) {
            ps.setFetchSize(dialect == null ? ROW_BATCH : dialect.streamingFetchSize(ROW_BATCH));
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columns = md.getColumnCount();
                boolean pgFamily = sourceCtx.getSourceFamily()
                        == com.qqmu.jync.model.DatabaseType.DialectFamily.POSTGRES;
                List<Map<String, Object>> batch = new ArrayList<>(ROW_BATCH);
                long batchBytes = 0L;
                long batchIndex = 0;
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(md.getColumnLabel(i), serializableValue(rs, i,
                                md.getColumnType(i), md.getColumnTypeName(i), pgFamily));
                    }
                    batch.add(row);
                    rows++;
                    batchBytes += estimateBytes(row);
                    if (batch.size() >= ROW_BATCH || batchBytes >= ROW_BATCH_BYTES) {
                        publishBatch(transport, sourceCtx, table, batch, batchIndex++, baselineRun);
                        batch = new ArrayList<>(ROW_BATCH);
                        batchBytes = 0L;
                    }
                }
                if (!batch.isEmpty()) {
                    publishBatch(transport, sourceCtx, table, batch, batchIndex, baselineRun);
                }
            }
            session.commit();
        }
        return rows;
    }

    /** Rough heap cost of one row; the exact wire size is a serialization detail. */
    private static long estimateBytes(Map<String, Object> row) {
        long bytes = 0L;
        for (Object value : row.values()) {
            if (value instanceof byte[] raw) {
                bytes += raw.length;
            } else if (value instanceof CharSequence text) {
                bytes += text.length() * 2L;
            } else {
                bytes += 32L;
            }
        }
        return bytes;
    }

    /**
     * Turn a raw driver value into something the serialized event can carry AND the target
     * binder understands. PG json/enum ({@code PGobject}) and arrays ({@code PgArray}) are
     * driver-private: read them as text exactly like the single-target full load does,
     * otherwise the decode allow-list rejects them (or the target driver cannot infer them).
     */
    private static Object serializableValue(ResultSet rs, int index, int jdbcType,
                                            String typeName, boolean pgFamily)
            throws SQLException {
        if (pgFamily && (jdbcType == java.sql.Types.OTHER
                || jdbcType == java.sql.Types.ARRAY)) {
            return rs.getString(index);
        }
        Object value = rs.getObject(index);
        if (value == null) {
            return null;
        }
        if (value.getClass().getName().startsWith("org.postgresql.util.")) {
            return value.toString();
        }
        if (value instanceof java.sql.Array array) {
            Object elements = array.getArray();
            if (elements instanceof Object[] objects) {
                return java.util.Arrays.deepToString(objects);
            }
            return String.valueOf(elements);
        }
        if (value instanceof Blob blob) {
            long length = blob.length();
            return blob.getBytes(1L, (int) Math.min(length, Integer.MAX_VALUE));
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            return clob.getSubString(1, (int) Math.min(length, Integer.MAX_VALUE));
        }
        if (value instanceof SQLXML xml) {
            return xml.getString();
        }
        return value;
    }

    private void publishBatch(EventTransport transport, SyncContext ctx, TableMeta table,
                              List<Map<String, Object>> batch, long index, String baselineRun)
            throws Exception {
        String token = "baseline:" + baselineRun + ":" + table.getName() + ":" + index;
        for (Map<String, Object> row : batch) {
            transport.publish(ChangeEvent.row(token, token, ctx.getSourceSchema(),
                    table.getName(), ChangeEvent.ChangeOperation.UPSERT, row, null,
                    System.currentTimeMillis()));
        }
        transport.publish(ChangeEvent.commit(token, token, System.currentTimeMillis()));
    }


}
