package com.synctool.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.synctool.config.SyncProperties;
import com.synctool.dto.ChangeEvent;
import com.synctool.dto.SyncResult;
import com.synctool.dto.meta.DatabaseMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.model.ChangeType;
import com.synctool.model.ObjectType;
import com.synctool.model.SyncProgress;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.service.monitor.ChangeDetector;
import com.synctool.service.monitor.CursorStrategy;
import com.synctool.service.monitor.CursorStrategyResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates one full sync cycle: detect structural changes, apply them, then move data.
 *
 * <p>Ordering matters. Structure is applied before data, so a newly added column exists before
 * the rows that populate it arrive. Within data sync, each table's cursor is persisted only
 * after that table's rows are committed to the target — see {@link DataSyncService} for why
 * that ordering is what makes concurrent source writes safe.
 */
@Service
@Slf4j
public class SyncEngine {

    private final DataSourceManager dataSourceManager;
    private final ChangeDetector changeDetector;
    private final CursorStrategyResolver cursorResolver;
    private final StructureSyncService structureSync;
    private final DataSyncService dataSync;
    private final SyncStateWriter stateWriter;
    private final SyncProperties properties;

    /**
     * When each table's row counts were last audited, keyed by {@code projectId:tableName}.
     *
     * <p>Held in memory on purpose. Losing it on restart costs one extra audit per table, which
     * is a good moment to run one anyway.
     */
    private final Map<String, Long> lastRowCountAudit = new ConcurrentHashMap<>();

    public SyncEngine(DataSourceManager dataSourceManager,
                      ChangeDetector changeDetector,
                      CursorStrategyResolver cursorResolver,
                      StructureSyncService structureSync,
                      DataSyncService dataSync,
                      SyncStateWriter stateWriter,
                      SyncProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.changeDetector = changeDetector;
        this.cursorResolver = cursorResolver;
        this.structureSync = structureSync;
        this.dataSync = dataSync;
        this.stateWriter = stateWriter;
        this.properties = properties;
    }

    /**
     * Runs one complete cycle.
     *
     * <p>The caller must already hold the project's sync lock. Exceptions are folded into the
     * result rather than propagated, so one failing project reports its error instead of
     * taking down the scheduler.
     */
    public SyncResult runCycle(SyncContext ctx) {
        long start = System.currentTimeMillis();
        SyncResult result = new SyncResult();

        try (Connection sourceConn = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection targetConn = dataSourceManager.getConnection(ctx.getTargetConfig())) {

            DatabaseMeta sourceMeta = readSourceMetadata(sourceConn, ctx);

            if (ctx.getConfig().isSyncStructure() || ctx.getConfig().isSyncViews()
                    || ctx.getConfig().isSyncProcedures()) {
                applyStructuralChanges(sourceMeta, targetConn, ctx, result);
            }

            if (ctx.getConfig().isSyncData()) {
                syncData(sourceMeta, sourceConn, targetConn, ctx, result);
            }

        } catch (SQLException e) {
            log.error("Sync cycle failed for project '{}': {}", ctx.projectName(), e.getMessage());
            result.addError("Connection or query failure: " + e.getMessage());
            stateWriter.recordLog(ctx.projectId(), ObjectType.PROJECT, ctx.projectName(),
                    ChangeType.ERROR, e.getMessage(), false, 0);
        } catch (RuntimeException e) {
            log.error("Sync cycle failed for project '{}'", ctx.projectName(), e);
            result.addError(String.valueOf(e.getMessage()));
            stateWriter.recordLog(ctx.projectId(), ObjectType.PROJECT, ctx.projectName(),
                    ChangeType.ERROR, String.valueOf(e.getMessage()), false, 0);
        }

        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private DatabaseMeta readSourceMetadata(Connection sourceConn, SyncContext ctx)
            throws SQLException {
        List<String> tables = ctx.getConfig().getTables().isEmpty() ? null
                : new ArrayList<>(ctx.getConfig().getTables());
        List<String> views = ctx.getConfig().getViews().isEmpty() ? null
                : new ArrayList<>(ctx.getConfig().getViews());
        List<String> procs = ctx.getConfig().getProcedures().isEmpty() ? null
                : new ArrayList<>(ctx.getConfig().getProcedures());

        DatabaseMeta meta = ctx.getSourceReader().readAll(sourceConn, ctx.getSourceSchema(),
                tables, views, procs,
                ctx.getConfig().isSyncViews(), ctx.getConfig().isSyncProcedures());

        // Row estimates decide whether full comparison is affordable, so collect them here
        // while the source connection is already open.
        if (ctx.getConfig().isSyncData()) {
            for (TableMeta table : meta.getTables()) {
                try {
                    table.setApproximateRowCount(ctx.getSourceReader()
                            .estimateRowCount(sourceConn, ctx.getSourceSchema(), table.getName()));
                } catch (SQLException e) {
                    log.debug("Row estimate failed for {}: {}", table.getName(), e.getMessage());
                }
            }
        }
        return meta;
    }

    /**
     * Applies each detected structural change, snapshotting per object as it succeeds.
     *
     * <p>Snapshotting object-by-object rather than in one final write means a run that dies
     * halfway keeps the objects it already applied and re-detects only the remainder.
     */
    private void applyStructuralChanges(DatabaseMeta sourceMeta, Connection targetConn,
                                        SyncContext ctx, SyncResult result) {
        List<ChangeEvent> events = changeDetector.detect(ctx.projectId(), sourceMeta,
                ctx.getConfig());
        if (events.isEmpty()) {
            return;
        }

        for (ChangeEvent event : events) {
            long eventStart = System.currentTimeMillis();
            StructureSyncService.ApplyOutcome outcome = structureSync.apply(targetConn, event, ctx);

            if (outcome.error != null) {
                result.addError(event + ": " + outcome.error);
                stateWriter.recordLog(ctx.projectId(), structureSync.logType(event.getObjectType()),
                        event.getObjectName(), event.getChangeType(), outcome.error, false,
                        System.currentTimeMillis() - eventStart);
                continue;
            }
            if (!outcome.applied) {
                log.debug("Skipped {}: {}", event, outcome.detail);
                continue;
            }

            result.setStructureChanges(result.getStructureChanges() + 1);
            stateWriter.recordLog(ctx.projectId(), structureSync.logType(event.getObjectType()),
                    event.getObjectName(), event.getChangeType(), outcome.detail, true,
                    System.currentTimeMillis() - eventStart);

            updateSnapshotFor(ctx.projectId(), event, sourceMeta);
        }
    }

    /**
     * Records the new source shape for an applied change.
     *
     * <p>Column and index events refresh the whole table snapshot, since snapshots are stored
     * per table and a partial update would make the next diff incorrect.
     */
    private void updateSnapshotFor(Long projectId, ChangeEvent event, DatabaseMeta sourceMeta) {
        boolean dropped = event.getChangeType() == ChangeType.DROP;
        switch (event.getObjectType()) {
            case TABLE:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.TABLE, event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.TABLE, event.getObjectName(),
                            event.getPayload());
                }
                break;
            case COLUMN:
            case INDEX: {
                TableMeta table = sourceMeta.table(event.getObjectName());
                if (table != null) {
                    stateWriter.saveSnapshot(projectId, ObjectType.TABLE, table.getName(), table);
                }
                break;
            }
            case VIEW:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.VIEW, event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.VIEW, event.getObjectName(),
                            event.getPayload());
                }
                break;
            case PROCEDURE:
            case FUNCTION:
                if (dropped) {
                    stateWriter.deleteSnapshot(projectId, ObjectType.PROCEDURE,
                            event.getObjectName());
                } else {
                    stateWriter.saveSnapshot(projectId, ObjectType.PROCEDURE,
                            event.getObjectName(), event.getPayload());
                }
                break;
            default:
                break;
        }
    }

    /**
     * Ensures the target table is present before data sync runs.
     *
     * <p>This is a safety net for when the target table has been manually dropped or the
     * metadata snapshot is out of sync with reality. Rather than failing the data sync with a
     * "table doesn't exist" error, we try an idempotent CREATE — if the table already exists the
     * database returns immediately, otherwise it gets built from the source definition using the
     * same DDL generator that structure sync uses (types, keys, indexes all correct).
     *
     * @return true on success (table was there or was just created); false on failure.
     */
    private boolean ensureTargetTableExists(TableMeta table, Connection targetConn,
                                            SyncContext ctx, SyncResult result) {
        String targetTable = ctx.getConfig().targetTableName(table.getName());
        ChangeEvent createEvent = ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE,
                table.getName(), "Auto-created before data sync (target table missing)", table);
        StructureSyncService.ApplyOutcome outcome =
                structureSync.apply(targetConn, createEvent, ctx);

        // ApplyOutcome.applied：ok→true, skipped→false（detail是原因）, failed→false（error是原因）
        if (!outcome.applied && outcome.error != null) {
            String msg = "Failed to create target table " + targetTable + ": " + outcome.error;
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, table.getName(),
                    ChangeType.ERROR, msg, false, 0);
            result.addError(table.getName() + ": " + outcome.error);
            return false;
        }

        // 只在真的创建了的时候记日志、更新快照。
        // executeIdempotentCreate 在表已存在时会返回 "Table already present: ..."，那种情况跳过记录。
        if (outcome.applied && outcome.detail != null && outcome.detail.startsWith("Created table")) {
            log.info("Auto-created missing target table {}", targetTable);
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, table.getName(),
                    ChangeType.CREATE, outcome.detail, true, 0);
            stateWriter.saveSnapshot(ctx.projectId(), ObjectType.TABLE, table.getName(), table);
        }
        return true;
    }

    /** Synchronizes rows for every selected table, one table at a time. */
    private void syncData(DatabaseMeta sourceMeta, Connection sourceConn, Connection targetConn,
                          SyncContext ctx, SyncResult result) {
        String disableSql = ctx.getConfig().isDisableTargetConstraints()
                ? ctx.getTargetDialect().getDisableConstraintsSql() : null;
        DdlExecutor executor = new DdlExecutor(targetConn);
        if (disableSql != null) {
            executor.executeQuietly(disableSql);
        }

        try {
            for (TableMeta table : structureSync.sortByDependency(sourceMeta.getTables())) {
                if (!ctx.getConfig().includesTable(table.getName())) {
                    continue;
                }
                syncOneTable(table, sourceConn, targetConn, ctx, result);
            }
        } finally {
            if (disableSql != null) {
                // Always restore enforcement, even when a table failed part-way through.
                executor.executeQuietly(ctx.getTargetDialect().getEnableConstraintsSql());
            }
        }
    }

    private void syncOneTable(TableMeta table, Connection sourceConn, Connection targetConn,
                              SyncContext ctx, SyncResult result) {
        long tableStart = System.currentTimeMillis();
        SyncProgress progress = stateWriter.loadOrCreateProgress(ctx.projectId(), table.getName());
        CursorStrategy strategy = cursorResolver.resolve(table, ctx.getConfig());

        // 目标表可能被手动删除了，或者快照与实际状态不同步。
        // 在开始数据同步前确认一下，缺失就用源表结构建一张，避免后续 upsert 整批失败。
        if (!ensureTargetTableExists(table, targetConn, ctx, result)) {
            return;
        }

        boolean firstLoad = progress.getInitialLoadDone() == null || !progress.getInitialLoadDone();
        if (firstLoad && ctx.getConfig().isTruncateBeforeInitialLoad()) {
            dataSync.truncateTarget(targetConn, table, ctx);
        }

        // 游标只能证明"我们读到哪儿了"，证明不了"目标真的收到了"。定期核对一次行数，
        // 目标少了就把游标清掉，让下一轮重新全量读一遍。
        auditRowCounts(table, sourceConn, targetConn, strategy, progress, ctx);

        DataSyncService.TableSyncResult tableResult = dataSync.syncTable(
                sourceConn, targetConn, table, strategy, progress, ctx);

        if (tableResult.isSkipped()) {
            log.debug("Skipping data sync for {}: {}", table.getName(), tableResult.getSkipReason());
            return;
        }

        if (!tableResult.isSuccess()) {
            result.addError(table.getName() + ": " + tableResult.getError());
            stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                    ChangeType.ERROR, tableResult.getError(), false,
                    System.currentTimeMillis() - tableStart);
            // The cursor is intentionally left untouched so this window is retried.
            return;
        }

        int deleted = 0;
        if (ctx.getConfig().isSyncDeletes()) {
            try {
                deleted = dataSync.syncDeletions(sourceConn, targetConn, table, ctx);
            } catch (SQLException e) {
                log.warn("Deletion sync failed for {}: {}", table.getName(), e.getMessage());
                result.addError(table.getName() + " (deletes): " + e.getMessage());
            }
        }

        // Advance the cursor only now that the target has committed the data. This ordering is
        // the core of the crash- and concurrency-safety argument.
        stateWriter.advanceCursor(progress, tableResult, strategy);

        result.setTablesProcessed(result.getTablesProcessed() + 1);
        int changed = tableResult.getRowsChanged();
        // Gate on rows the target actually changed. A full-compare table re-upserts every row
        // on every cycle, and each of those upserts is a no-op when nothing moved — logging the
        // scan size instead would post an identical "51 row(s)" entry every couple of seconds.
        if (changed > 0) {
            result.addTableRows(table.getName(), changed);

            int inserted = tableResult.getRowsInserted();
            int updated = tableResult.getRowsUpdated();
            // Rows the target changed without saying how. Attribute them by phase, which is the
            // best available guess: an initial load into an empty table is all inserts, and a
            // later window is more often revisions of rows already delivered.
            int unclassified = tableResult.getRowsChangedUnclassified();
            if (unclassified > 0) {
                if (tableResult.isInitialLoad()) {
                    inserted += unclassified;
                } else {
                    updated += unclassified;
                }
            }

            // One entry per kind of write that actually happened, so a cycle that inserts some
            // rows and revises others is not filed wholesale under whichever came first.
            long durationMs = System.currentTimeMillis() - tableStart;
            if (inserted > 0) {
                result.setRowsInserted(result.getRowsInserted() + inserted);
                recordDataLog(ctx, table, strategy, tableResult, ChangeType.INSERT, inserted,
                        durationMs);
            }
            if (updated > 0) {
                result.setRowsUpdated(result.getRowsUpdated() + updated);
                recordDataLog(ctx, table, strategy, tableResult, ChangeType.UPDATE, updated,
                        durationMs);
            }
        }
        if (deleted > 0) {
            result.setRowsDeleted(result.getRowsDeleted() + deleted);
            stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                    ChangeType.DELETE, deleted + " row(s) removed to match source", true, 0,
                    deleted);
        }

        // Keep the table snapshot current even when only data moved, so a later structural
        // diff is not confused by a stale shape.
        stateWriter.saveSnapshot(ctx.projectId(), ObjectType.TABLE, table.getName(), table);
    }

    /**
     * Checks that the target really holds every row the cursor claims to have delivered, and
     * clears the cursor when it does not.
     *
     * <p>This exists because an incremental cursor is a claim, not a proof. An identity cursor
     * only ever looks for higher ids, so rows that appear at the source <em>below</em> it are
     * invisible forever — which is precisely what happens when a source table is truncated and
     * reseeded, since {@code TRUNCATE} resets {@code AUTO_INCREMENT} and the replacement rows
     * land underneath a watermark the target never received. Nothing in the sync can notice
     * that by reading the window, so the row counts have to be compared directly.
     *
     * <p>Clearing the cursor is the whole repair: the next pass sees no cursor, takes the
     * full-load path, and re-upserts the table. Failures here are logged and swallowed — an
     * audit that cannot run must not stop the sync it was meant to protect.
     */
    private void auditRowCounts(TableMeta table, Connection sourceConn, Connection targetConn,
                                CursorStrategy strategy, SyncProgress progress, SyncContext ctx) {
        long interval = properties.getRowCountAuditIntervalMs();
        if (interval <= 0) {
            return;
        }
        // COUNT(*) is a full index scan on InnoDB, and the poll cycle is measured in seconds.
        // Throttling per table keeps the audit's cost proportional to its usefulness.
        String key = ctx.projectId() + ":" + table.getName();
        long now = System.currentTimeMillis();
        Long previous = lastRowCountAudit.get(key);
        if (previous != null && now - previous < interval) {
            return;
        }
        lastRowCountAudit.put(key, now);

        try {
            DataSyncService.RowCountAudit audit = dataSync.auditSyncedRowCounts(
                    sourceConn, targetConn, table, strategy, progress, ctx);
            if (audit == null || !audit.isTargetShort()) {
                return;
            }
            long missing = audit.getSourceRows() - audit.getTargetRows();
            // Read before clearing: clearCursor nulls this field on the same instance.
            String cursor = progress.getLastSyncValue();
            log.warn("Row count audit failed for {}: source has {} row(s) at or below cursor {}"
                            + " but target has {} — forcing a full reload of this table",
                    table.getName(), audit.getSourceRows(), cursor, audit.getTargetRows());
            stateWriter.clearCursor(progress);
            stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(),
                    ChangeType.INFO,
                    "Row count audit: target is missing " + missing + " row(s) already marked as"
                            + " synced (source " + audit.getSourceRows() + " vs target "
                            + audit.getTargetRows() + " at or below cursor " + cursor
                            + "). Cursor cleared; the table will be fully reloaded.",
                    true, 0, (int) Math.min(missing, Integer.MAX_VALUE));
        } catch (SQLException e) {
            // Worth knowing about, but not worth failing the table over.
            log.warn("Row count audit could not run for {}: {}", table.getName(), e.getMessage());
        }
    }

    /**
     * Writes one audit entry for rows of a single kind — inserted or updated.
     *
     * <p>Full-compare rescans a whole table to find a handful of changes, so the scan size is
     * stated alongside the change count whenever the two differ: "1 row(s) (51 scanned)" says
     * what the cycle achieved and what it cost, where "1 row(s)" alone would hide the latter.
     */
    private void recordDataLog(SyncContext ctx, TableMeta table, CursorStrategy strategy,
                               DataSyncService.TableSyncResult tableResult, ChangeType changeType,
                               int rows, long durationMs) {
        String scanned = tableResult.getRowsWritten() > rows
                ? " (" + tableResult.getRowsWritten() + " scanned)" : "";
        String phase = tableResult.isInitialLoad() ? "Initial load: " : "Incremental sync: ";
        String verb = changeType == ChangeType.INSERT ? " inserted" : " updated";
        stateWriter.recordLog(ctx.projectId(), ObjectType.DATA, table.getName(), changeType,
                phase + rows + " row(s)" + verb + scanned + " via "
                        + strategy.getKind().name().toLowerCase() + " strategy"
                        + (strategy.getColumn() != null ? " on " + strategy.getColumn() : ""),
                true, durationMs, rows);
    }

    /** Clears all progress and snapshots, forcing the next run to do a full reload. */
    public void resetProject(Long projectId) {
        stateWriter.resetProject(projectId);
    }

    /** Batch size for a project, honouring its override. */
    public int resolveBatchSize(Integer projectOverride) {
        if (projectOverride != null && projectOverride > 0) {
            return projectOverride;
        }
        return properties.getBatchSize();
    }
}
