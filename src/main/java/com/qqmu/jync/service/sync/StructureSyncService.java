package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.converter.SqlBodyConverter;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.util.JdbcUtil;
import com.qqmu.jync.util.TargetTableVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies structural changes to the target database.
 *
 * <p>Each change is applied independently: one failing object does not abort the rest, since
 * a single unconvertible stored procedure should not block table and data synchronization.
 * Failures are returned as messages for the caller to record in the change log.
 */
@Service
@Slf4j
public class StructureSyncService {

    private final SqlBodyConverter bodyConverter;

    /**
     * Secondary indexes whose creation failed while a table was being created, keyed
     * {@code projectId|sourceTableName}.
     *
     * <p>Why this exists: the table snapshot is saved after a successful applyTable even when an
     * index failed (an index is performance, not correctness, so data sync must proceed), and
     * once saved no later source-vs-snapshot diff ever mentions that index — without this queue
     * a transient lock/timeout failure was silently permanent. The engine retries the queue once
     * per cycle through {@link #retryPendingIndexes}. In-memory by design: a restart loses the
     * queue, and the table then simply runs without that index until re-created or re-detected.
     */
    private final Map<String, List<PendingIndex>> pendingIndexes = new ConcurrentHashMap<>();

    /**
     * A queued index plus the table it belongs to. The retry runs in a later cycle with no
     * structural event in hand, and MySQL needs the table's shape to know which of its
     * columns became LONGTEXT and therefore need a key length.
     */
    private record PendingIndex(IndexMeta index, TableMeta sourceTable) {
    }

    public StructureSyncService(SqlBodyConverter bodyConverter) {
        this.bodyConverter = bodyConverter;
    }

    /**
     * Retries index creations that failed in earlier cycles; returns human-readable details of
     * the ones that recovered, so the engine can file change-log entries.
     */
    public List<String> retryPendingIndexes(Connection targetConn, SyncContext ctx) {
        if (pendingIndexes.isEmpty()) {
            return List.of();
        }
        long projectId = ctx.projectId();
        List<String> recovered = new ArrayList<>();
        DdlExecutor executor = new DdlExecutor(targetConn);
        Iterator<Map.Entry<String, List<PendingIndex>>> entries =
                pendingIndexes.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, List<PendingIndex>> entry = entries.next();
            String sourceTable = tableOfKey(entry.getKey());
            if (projectOfKey(entry.getKey()) != projectId) {
                continue;
            }
            Iterator<PendingIndex> pending = entry.getValue().iterator();
            while (pending.hasNext()) {
                PendingIndex failed = pending.next();
                String indexSql = ctx.getTargetDialect().getCreateIndexSql(failed.index(),
                        ctx.getTargetSchema(), ctx.getConfig().targetTableName(sourceTable),
                        failed.sourceTable(), ctx.effectiveSourceType());
                if (indexSql == null) {
                    // Retrying cannot help: the dialect refuses this index by design.
                    pending.remove();
                    continue;
                }
                try {
                    executor.executeIdempotentCreate(indexSql);
                    pending.remove();
                    recovered.add("Created previously-failed index " + failed.index().getName()
                            + " on " + sourceTable);
                } catch (SQLException e) {
                    log.warn("Retry of index {} on {} still failing: {}",
                            failed.index().getName(), sourceTable, e.getMessage());
                }
            }
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
        return recovered;
    }

    /**
     * Builds the non-unique secondary indexes that were held back when a newly created table
     * took its initial full load. Called after the load has committed (before the cursor is
     * advanced, so a crash in this window repeats the load instead of losing the indexes), and
     * also when the table's data sync is skipped — no load is coming, so the indexes are owed
     * now rather than deferred forever.
     *
     * <p>Idempotent: a resumed load, a table whose indexes were created up front, or a second
     * call after a partial success simply reports what it actually created. A failing index is
     * queued like any other index failure — data is already safely in the target.
     *
     * @return number of indexes actually created by this call
     */
    public int createDeferredIndexes(Connection targetConn, SyncContext ctx, TableMeta sourceTable) {
        if (!ctx.isDeferSecondaryIndexes() || !ctx.getConfig().isSyncIndexes()) {
            return 0;
        }
        String targetTable = ctx.getConfig().targetTableName(sourceTable.getName());
        DdlExecutor executor = new DdlExecutor(targetConn);
        int created = 0;
        for (IndexMeta index : sourceTable.secondaryIndexes()) {
            if (index.getColumns().isEmpty() || index.isUnique()) {
                continue;
            }
            String indexSql = ctx.getTargetDialect().getCreateIndexSql(index,
                    ctx.getTargetSchema(), targetTable, sourceTable, ctx.effectiveSourceType());
            if (indexSql == null) {
                continue;
            }
            try {
                if (executor.executeIdempotentCreate(indexSql)) {
                    created++;
                }
            } catch (SQLException e) {
                log.warn("Could not create deferred index {} on {}: {}",
                        index.getName(), targetTable, e.getMessage());
                queueFailedIndex(ctx.projectId(), sourceTable, index);
            }
        }
        if (created > 0) {
            log.info("Created {} deferred secondary index(es) on {} after the initial load",
                    created, targetTable);
        }
        return created;
    }

    /** Records an index that could not be created with its table, de-duplicated by index name. */
    private void queueFailedIndex(long projectId, TableMeta sourceTable, IndexMeta index) {
        String key = pendingKey(projectId, sourceTable.getName());
        pendingIndexes.compute(key, (k, existing) -> {
            List<PendingIndex> list = existing == null ? new ArrayList<>() : existing;
            for (PendingIndex queued : list) {
                if (queued.index().getName().equalsIgnoreCase(index.getName())) {
                    return list;
                }
            }
            list.add(new PendingIndex(index, sourceTable));
            return list;
        });
    }

    /**
     * Whether this table still has an index creation queued for retry.
     *
     * <p>The queue is in memory, so the table snapshot must not be certified as converged while
     * it is non-empty: after a restart the queue is gone, and a saved snapshot would tell the
     * detector the index landed — leaving the target permanently without it.
     */
    public boolean hasPendingIndexes(long projectId, String sourceTable) {
        List<PendingIndex> queued = pendingIndexes.get(pendingKey(projectId, sourceTable));
        return queued != null && !queued.isEmpty();
    }

    /** Drops the retry queue for a project (project deleted or reset). */
    public void evictProject(long projectId) {
        String prefix = projectId + "|";
        pendingIndexes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String pendingKey(long projectId, String sourceTable) {
        return projectId + "|" + sourceTable;
    }

    private static long projectOfKey(String key) {
        return Long.parseLong(key.substring(0, key.indexOf('|')));
    }

    private static String tableOfKey(String key) {
        return key.substring(key.indexOf('|') + 1);
    }

    /** Result of applying one structural change. */
    public static class ApplyOutcome {
        public final boolean applied;
        public final String detail;
        public final String error;

        private ApplyOutcome(boolean applied, String detail, String error) {
            this.applied = applied;
            this.detail = detail;
            this.error = error;
        }

        static ApplyOutcome ok(String detail) {
            return new ApplyOutcome(true, detail, null);
        }

        static ApplyOutcome skipped(String reason) {
            return new ApplyOutcome(false, reason, null);
        }

        static ApplyOutcome failed(String error) {
            return new ApplyOutcome(false, null, error);
        }
    }

    /**
     * Applies one detected change to the target.
     *
     * @param ctx the resolved source/target context for the project
     */
    public ApplyOutcome apply(Connection targetConn, ChangeEvent event, SyncContext ctx) {
        DdlExecutor executor = new DdlExecutor(targetConn);
        try {
            switch (event.getObjectType()) {
                case TABLE:
                    return applyTable(targetConn, executor, event, ctx);
                case COLUMN:
                    return applyColumn(executor, event, ctx);
                case INDEX:
                    return applyIndex(executor, event, ctx);
                case VIEW:
                    return applyView(executor, event, ctx);
                case PROCEDURE:
                case FUNCTION:
                    return applyProcedure(executor, event, ctx);
                default:
                    return ApplyOutcome.skipped("Unsupported object type " + event.getObjectType());
            }
        } catch (SQLException e) {
            log.warn("Failed to apply {} on {}: {}", event.getChangeType(), event.getObjectName(),
                    e.getMessage());
            return ApplyOutcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to apply {} on {}: {}", event.getChangeType(), event.getObjectName(),
                    e.getMessage());
            return ApplyOutcome.failed(String.valueOf(e.getMessage()));
        }
    }

    private ApplyOutcome applyTable(Connection targetConn, DdlExecutor executor,
                                    ChangeEvent event, SyncContext ctx) throws SQLException {
        TableMeta table = (TableMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        if (event.getChangeType() == ChangeType.DROP) {
            if (!ctx.getConfig().isAllowDrop()) {
                return ApplyOutcome.skipped("DROP not permitted by project settings");
            }
            executor.execute(dialect.getDropTableSql(ctx.getTargetSchema(), targetTable), true);
            // The table is gone, so an index queued for it can never be created: retrying it
            // every cycle would fail forever, and while the entry sits in the queue the engine
            // refuses to retire the table's snapshot — the DROP would be re-detected, re-run
            // and re-logged every cycle without ever converging.
            pendingIndexes.remove(pendingKey(ctx.projectId(), table.getName()));
            return ApplyOutcome.ok("Dropped table " + targetTable);
        }

        // Tables have no override: the review/override feature only covers views and routines,
        // and saveOverride rejects every other kind, so a TABLE key can never be created.
        String createSql = dialect.getCreateTableSql(table, ctx.getTargetSchema(), targetTable,
                ctx.effectiveSourceType());

        // Tolerate "already exists": the target may have been created by a previous run that
        // crashed before its snapshot was written, or pre-created by hand.
        boolean created = executor.executeIdempotentCreate(createSql);

        // 表本来就在，并不意味着它装得下源端的行：手工预建、或者上一版跑出来的表都可能少列
        // （实测：源库后来加了 gender，目标的 TEACHER 还是老结构）。缺列是纯增量差异，补上
        // 不动任何已存在的数据；不补的话这张表会永远卡住 —— 结构同步只会 CREATE（对已存在的
        // 表不生效），数据侧每轮报一次 missing column，快照又被失败扣住，谁都不会好转。
        List<String> addedColumns = created ? List.of()
                : addMissingColumns(targetConn, ctx, targetTable, table);

        // A comment edited at the source reaches the target only through an explicit ALTER:
        // CREATE TABLE ... COMMENT is a no-op once the table exists, and the comment is not
        // part of any column signature. The detector emits an ALTER event for that case, and
        // this makes the event's DDL do something. Cosmetic by nature, so a failure here is
        // reported but must not block data sync (the snapshot would then be withheld and the
        // same failing ALTER retried forever).
        if (!created) {
            applyTableComment(executor, dialect, ctx, targetTable, table);
        }

        // Version-gated companions: sequence + trigger emulating an identity column on
        // pre-12c Oracle. A failure here blocks the table (its autoincrement contract is
        // unfulfilled), so let the SQLException surface to apply()'s failure handling.
        for (String extra : dialect.getPostCreateTableDdl(table, ctx.getTargetSchema(),
                targetTable, ctx.effectiveSourceType())) {
            executor.executeIdempotentCreate(extra);
        }

        int indexCount = 0;
        int deferred = 0;
        if (ctx.getConfig().isSyncIndexes()) {
            // A brand-new table is empty and is about to take its initial full load in this
            // same cycle. Non-unique indexes are pure write cost while the rows arrive, so
            // hold them back until the load has committed (see createDeferredIndexes).
            boolean deferSecondary = ctx.isDeferSecondaryIndexes() && created
                    && ctx.getConfig().isSyncData();
            boolean anyQueued = false;
            for (IndexMeta index : table.secondaryIndexes()) {
                if (index.getColumns().isEmpty()) {
                    continue;
                }
                if (deferSecondary && !index.isUnique()) {
                    deferred++;
                    continue;
                }
                String indexSql = dialect.getCreateIndexSql(index, ctx.getTargetSchema(),
                        targetTable, table, ctx.effectiveSourceType());
                if (indexSql == null) {
                    // The dialect already explained why; data sync takes priority over it.
                    continue;
                }
                try {
                    executor.executeIdempotentCreate(indexSql);
                    indexCount++;
                } catch (SQLException e) {
                    // A failed index is a performance problem, not a correctness one, so data
                    // sync proceeds — but queue it for retry instead of giving up permanently.
                    log.warn("Could not create index {} on {}: {}", index.getName(), targetTable,
                            e.getMessage());
                    queueFailedIndex(ctx.projectId(), table, index);
                    anyQueued = true;
                }
            }
            if (!anyQueued) {
                // Every index is now present: anything still queued is stale (e.g. the index
                // was renamed and recreated).
                pendingIndexes.remove(pendingKey(ctx.projectId(), table.getName()));
            }
        }
        String detail = (created ? "Created table " : "Table already present: ") + targetTable
                + (addedColumns.isEmpty() ? "" : ", added column(s) " + String.join(", ", addedColumns))
                + (indexCount > 0 ? " with " + indexCount + " index(es)" : "")
                + (deferred > 0 ? ", " + deferred
                        + " secondary index(es) deferred until the initial load completes" : "");
        return ApplyOutcome.ok(detail);
    }

    /**
     * Adds the source columns the target table does not have; returns the names it added.
     *
     * <p>Purely additive: existing columns keep their type and nothing is dropped, so a table
     * that carries extra business columns of its own keeps them. A missing column is the one
     * shape difference that can be repaired without a human decision, which is why it is done
     * here instead of being reported. Wrong types and a {@code GENERATED ALWAYS} identity
     * column are not touched — those still come back as a shape-check error for the user.
     *
     * @throws SQLException when an ADD fails. The caller must not swallow it: a table whose
     *     missing mandatory column could not be added still cannot take the source's rows, and
     *     reporting success would have the engine save the snapshot and lose the retry record.
     */
    public List<String> addMissingColumns(Connection targetConn, SyncContext ctx,
                                          String targetTable, TableMeta table)
            throws SQLException {
        SqlDialect dialect = ctx.getTargetDialect();
        List<ColumnMeta> missing = TargetTableVerifier.missingColumns(targetConn,
                ctx.getTargetSchema(), targetTable, table, ctx.effectiveTargetType());
        if (missing.isEmpty()) {
            return List.of();
        }
        DdlExecutor executor = new DdlExecutor(targetConn);
        List<String> added = new ArrayList<>();
        for (ColumnMeta column : missing) {
            // Idempotent: two cycles can race to add the same column, and "already there" is
            // the desired end state just as it is for a table.
            executor.executeIdempotentCreate(dialect.getAddColumnSql(
                    ctx.getTargetSchema(), targetTable, column, ctx.effectiveSourceType(), table));
            runPostColumnDdl(executor, dialect, targetTable, column, ctx);
            added.add(column.getName());
        }
        log.info("Added missing column(s) {} to target table {} (project {})",
                String.join(", ", added), targetTable, ctx.projectId());
        return added;
    }

    /** Sets the existing target table's comment to match the source (null/blank clears it). */
    private void applyTableComment(DdlExecutor executor, SqlDialect dialect, SyncContext ctx,
                                   String targetTable, TableMeta table) {
        String sql;
        try {
            sql = dialect.getAlterTableCommentSql(ctx.getTargetSchema(), targetTable,
                    table.getRemarks());
            if (sql == null) {
                return;
            }
            executor.execute(sql, false);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not update the comment of target table {}: {}",
                    targetTable, e.getMessage());
        }
    }

    /** Runs the dialect's ADD/MODIFY companions (pre-12c Oracle identity emulation). */
    private void runPostColumnDdl(DdlExecutor executor, SqlDialect dialect, String targetTable,
                                  ColumnMeta column, SyncContext ctx) throws SQLException {
        for (String extra : dialect.getPostColumnChangeDdl(
                ctx.getTargetSchema(), targetTable, column, ctx.effectiveSourceType())) {
            executor.executeIdempotentCreate(extra);
        }
    }

    private ApplyOutcome applyColumn(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        ColumnMeta column = (ColumnMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(event.getObjectName());

        switch (event.getChangeType()) {
            case CREATE:
                // The source table travels with the event so a target with a whole-table
                // constraint (MySQL's row-size budget) keeps the type this table's plan
                // picked for the column rather than re-mapping it in isolation.
                executor.executeIdempotentCreate(dialect.getAddColumnSql(
                        ctx.getTargetSchema(), targetTable, column, ctx.effectiveSourceType(),
                        event.getSourceTable()));
                runPostColumnDdl(executor, dialect, targetTable, column, ctx);
                return ApplyOutcome.ok("Added column " + column.getName() + " to " + targetTable);
            case ALTER:
                if (!dialect.supportsAlterColumnType()) {
                    return ApplyOutcome.skipped("Target dialect cannot alter column types in place");
                }
                // A comment-only edit reissues the column definition: re-mapping the source
                // type here would undo the narrowing and blow the row-size budget.
                executor.execute(dialect.getModifyColumnSql(
                        ctx.getTargetSchema(), targetTable, column, ctx.effectiveSourceType(),
                        event.getSourceTable()), false);
                runPostColumnDdl(executor, dialect, targetTable, column, ctx);
                return ApplyOutcome.ok("Modified column " + column.getName() + " on " + targetTable);
            case DROP:
                if (!ctx.getConfig().isAllowDrop()) {
                    return ApplyOutcome.skipped("Column DROP not permitted by project settings");
                }
                executor.execute(dialect.getDropColumnSql(
                        ctx.getTargetSchema(), targetTable, column.getName()), true);
                return ApplyOutcome.ok("Dropped column " + column.getName() + " from " + targetTable);
            default:
                return ApplyOutcome.skipped("Unsupported column change " + event.getChangeType());
        }
    }

    private ApplyOutcome applyIndex(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        IndexMeta index = (IndexMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(event.getObjectName());

        if (event.getChangeType() == ChangeType.DROP) {
            if (!ctx.getConfig().isAllowDrop()) {
                return ApplyOutcome.skipped("Index DROP not permitted by project settings");
            }
            executor.execute(dialect.getDropIndexSql(index, ctx.getTargetSchema(), targetTable), true);
            return ApplyOutcome.ok("Dropped index " + index.getName());
        }

        String indexSql = dialect.getCreateIndexSql(index, ctx.getTargetSchema(), targetTable,
                event.getSourceTable(), ctx.effectiveSourceType());
        if (indexSql == null) {
            // Checked before the DROP of an ALTER so an unusable definition is not first
            // dropped and then found impossible to recreate.
            return ApplyOutcome.skipped("Index " + index.getName()
                    + " cannot be created on the target; data sync takes priority");
        }
        if (event.getChangeType() == ChangeType.ALTER) {
            // An index definition cannot be changed in place; drop then recreate.
            executor.execute(dialect.getDropIndexSql(index, ctx.getTargetSchema(), targetTable), true);
        }
        executor.executeIdempotentCreate(indexSql);
        return ApplyOutcome.ok((event.getChangeType() == ChangeType.ALTER ? "Recreated" : "Created")
                + " index " + index.getName() + " on " + targetTable);
    }

    private ApplyOutcome applyView(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        ViewMeta view = (ViewMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();

        if (event.getChangeType() == ChangeType.DROP) {
            if (!ctx.getConfig().isAllowDrop()) {
                return ApplyOutcome.skipped("View DROP not permitted by project settings");
            }
            executor.execute(dialect.getDropViewSql(ctx.getTargetSchema(), view.getName()), true);
            return ApplyOutcome.ok("Dropped view " + view.getName());
        }

        String override = ctx.getConfig().ddlOverride("VIEW", view.getName());
        List<String> statements;
        if (override != null && !override.isBlank()) {
            // A view is a plain SQL statement, so a pasted/model-supplied trailing semicolon is a
            // terminator, not syntax: Oracle rejects it on DDL submitted through JDBC.
            statements = List.of(JdbcUtil.stripTrailingSemicolon(override));
        } else {
            // Normalize to a bare SELECT, then translate it into the target's dialect.
            ViewMeta translated = new ViewMeta();
            translated.setName(view.getName());
            translated.setSchema(ctx.getTargetSchema());
            String body = bodyConverter.extractViewBody(view.getDefinition());
            translated.setDefinition(bodyConverter.convert(body, ctx.effectiveSourceType(),
                    ctx.effectiveTargetType()));
            statements = dialect.getCreateViewSql(translated, ctx.getTargetSchema(),
                    ctx.effectiveSourceType());
        }
        if (statements.isEmpty()) {
            return ApplyOutcome.skipped("No view definition available to apply");
        }
        executor.executeReplaceSequence(statements);
        return ApplyOutcome.ok((event.getChangeType() == ChangeType.CREATE ? "Created" : "Replaced")
                + " view " + view.getName());
    }

    private ApplyOutcome applyProcedure(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        ProcedureMeta proc = (ProcedureMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();

        if (event.getChangeType() == ChangeType.DROP) {
            if (!ctx.getConfig().isAllowDrop()) {
                return ApplyOutcome.skipped("Routine DROP not permitted by project settings");
            }
            executor.execute(dialect.getDropProcedureSql(ctx.getTargetSchema(), proc.getName(),
                    proc.isFunction()), true);
            return ApplyOutcome.ok("Dropped " + proc.getRoutineType() + " " + proc.getName());
        }

        String override = ctx.getConfig().ddlOverride("PROCEDURE", proc.getName());
        List<String> statements;
        if (override != null && !override.isBlank()) {
            statements = List.of(override);
        } else {
            // Old configs (or a hand-edited selection) can still name an Oracle package member
            // ("PKG.PROC"): package routines were listed under their stripped short name. The
            // source reader cannot read them as standalone routines and the target has no
            // package container, so say precisely what to do instead of failing later with an
            // empty body.
            if (proc.getName() != null && proc.getName().indexOf('.') >= 0) {
                return ApplyOutcome.failed("Routine " + proc.getName() + " is a member of an"
                        + " Oracle package; package members cannot be converted to standalone"
                        + " routines automatically. Create the target routine manually (or via a"
                        + " DDL override keyed to this name) or remove it from the selection.");
            }
            ProcedureMeta translated = new ProcedureMeta();
            translated.setName(proc.getName());
            translated.setSchema(ctx.getTargetSchema());
            translated.setRoutineType(proc.getRoutineType());
            translated.setReturnType(proc.getReturnType());
            translated.setParameters(proc.getParameters());
            translated.setDefinition(bodyConverter.convert(proc.getDefinition(),
                    ctx.effectiveSourceType(), ctx.effectiveTargetType()));
            statements = dialect.getCreateProcedureSql(translated, ctx.getTargetSchema(),
                    ctx.effectiveSourceType());
        }
        if (statements.isEmpty()) {
            return ApplyOutcome.skipped("No routine body available to apply");
        }
        try {
            executor.executeReplaceSequence(statements);
        } catch (SQLException e) {
            // Procedural syntax often cannot be translated automatically. Report precisely so
            // the user can paste a corrected body as a DDL override.
            return ApplyOutcome.failed("Automatic conversion of " + proc.getRoutineType() + " "
                    + proc.getName() + " failed (" + e.getMessage()
                    + "). Provide a manual DDL override for this routine.");
        }
        return ApplyOutcome.ok((event.getChangeType() == ChangeType.CREATE ? "Created" : "Replaced")
                + " " + proc.getRoutineType() + " " + proc.getName());
    }

    /** Tables referenced by foreign keys must exist first; sorts by dependency depth. */
    public List<TableMeta> sortByDependency(List<TableMeta> tables) {
        List<TableMeta> sorted = new ArrayList<>(tables);
        // Tables with no outgoing foreign keys first, then by number of dependencies. A full
        // topological sort is unnecessary because CREATE TABLE here never emits FK clauses;
        // this ordering only improves the odds for targets that infer constraints.
        sorted.sort((a, b) -> Integer.compare(
                a.getForeignKeys() == null ? 0 : a.getForeignKeys().size(),
                b.getForeignKeys() == null ? 0 : b.getForeignKeys().size()));
        return sorted;
    }

    /** Maps object type to the change-log entry type, folding FUNCTION into PROCEDURE. */
    public ObjectType logType(ObjectType type) {
        return type == ObjectType.FUNCTION ? ObjectType.PROCEDURE : type;
    }
}
