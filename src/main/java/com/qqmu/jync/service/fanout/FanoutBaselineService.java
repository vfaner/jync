package com.qqmu.jync.service.fanout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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

    private final StructureSyncService structureSync;
    private final FanoutProgressStore progressStore;

    public FanoutBaselineService(StructureSyncService structureSync,
                                 FanoutProgressStore progressStore) {
        this.structureSync = structureSync;
        this.progressStore = progressStore;
    }

    /** One target's id + context; the context resolves that target's dialect and schema. */
    public record Target(Long targetDbId, SyncContext context) {
    }

    public void baseline(SyncContext sourceCtx, Connection sourceConn,
                         List<Target> targets, EventTransport transport) throws Exception {
        if (targets == null || targets.isEmpty()) {
            return;
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
        for (TableMeta table : keyed) {
            long rows = publishTable(sourceCtx, sourceConn, table, transport);
            for (Target target : targets) {
                markLoaded(sourceCtx.projectId(), target.targetDbId(), table.getName(), rows);
            }
            log.info("Fan-out baseline: {} copied {} row(s) to {} target(s)",
                    table.getName(), rows, targets.size());
        }
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
                              EventTransport transport) throws Exception {
        String sql = "SELECT * FROM " + sourceCtx.getSourceDialect()
                .qualify(sourceCtx.getSourceSchema(), table.getName());
        long rows = 0;
        try (PreparedStatement ps = sourceConn.prepareStatement(sql)) {
            ps.setFetchSize(ROW_BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columns = md.getColumnCount();
                List<Map<String, Object>> batch = new ArrayList<>(ROW_BATCH);
                long batchIndex = 0;
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    batch.add(row);
                    rows++;
                    if (batch.size() >= ROW_BATCH) {
                        publishBatch(transport, sourceCtx, table, batch, batchIndex++);
                        batch = new ArrayList<>(ROW_BATCH);
                    }
                }
                if (!batch.isEmpty()) {
                    publishBatch(transport, sourceCtx, table, batch, batchIndex);
                }
            }
        }
        return rows;
    }

    private void publishBatch(EventTransport transport, SyncContext ctx, TableMeta table,
                              List<Map<String, Object>> batch, long index) throws Exception {
        String token = "baseline:" + table.getName() + ":" + index;
        for (Map<String, Object> row : batch) {
            transport.publish(ChangeEvent.row(token, token, ctx.getSourceSchema(),
                    table.getName(), ChangeEvent.ChangeOperation.UPSERT, row, null,
                    System.currentTimeMillis()));
        }
        transport.publish(ChangeEvent.commit(token, token, System.currentTimeMillis()));
    }

    private void markLoaded(Long projectId, Long targetDbId, String table, long rows) {
        FanoutProgress progress = progressStore.loadOrCreate(projectId, targetDbId, table);
        progress.setInitialLoadDone(true);
        progress.setRowsSyncedTotal(rows);
        progress.setLastSyncTime(Instant.now());
        progressStore.save(progress);
    }
}
