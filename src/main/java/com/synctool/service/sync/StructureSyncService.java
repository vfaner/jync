package com.synctool.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.synctool.dto.ChangeEvent;
import com.synctool.dto.SyncConfig;
import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.IndexMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.ChangeType;
import com.synctool.model.DatabaseType;
import com.synctool.model.ObjectType;
import com.synctool.service.converter.SqlBodyConverter;
import com.synctool.service.converter.SqlDialect;

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

    public StructureSyncService(SqlBodyConverter bodyConverter) {
        this.bodyConverter = bodyConverter;
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
                    return applyTable(executor, event, ctx);
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

    private ApplyOutcome applyTable(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        TableMeta table = (TableMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(table.getName());

        if (event.getChangeType() == ChangeType.DROP) {
            if (!ctx.getConfig().isAllowDrop()) {
                return ApplyOutcome.skipped("DROP not permitted by project settings");
            }
            executor.execute(dialect.getDropTableSql(ctx.getTargetSchema(), targetTable), true);
            return ApplyOutcome.ok("Dropped table " + targetTable);
        }

        // A user-supplied DDL override takes precedence over generated SQL.
        String override = ctx.getConfig().ddlOverride("TABLE", table.getName());
        String createSql = override != null && !override.isBlank()
                ? override
                : dialect.getCreateTableSql(table, ctx.getTargetSchema(), targetTable,
                        ctx.getSourceType());

        // Tolerate "already exists": the target may have been created by a previous run that
        // crashed before its snapshot was written.
        boolean created = executor.executeIdempotentCreate(createSql);

        int indexCount = 0;
        if (ctx.getConfig().isSyncIndexes()) {
            for (IndexMeta index : table.secondaryIndexes()) {
                if (index.getColumns().isEmpty()) {
                    continue;
                }
                try {
                    executor.executeIdempotentCreate(
                            dialect.getCreateIndexSql(index, ctx.getTargetSchema(), targetTable));
                    indexCount++;
                } catch (SQLException e) {
                    // A failed index is a performance problem, not a correctness one.
                    log.warn("Could not create index {} on {}: {}", index.getName(), targetTable,
                            e.getMessage());
                }
            }
        }
        String detail = (created ? "Created table " : "Table already present: ") + targetTable
                + (indexCount > 0 ? " with " + indexCount + " index(es)" : "");
        return ApplyOutcome.ok(detail);
    }

    private ApplyOutcome applyColumn(DdlExecutor executor, ChangeEvent event, SyncContext ctx)
            throws SQLException {
        ColumnMeta column = (ColumnMeta) event.getPayload();
        SqlDialect dialect = ctx.getTargetDialect();
        String targetTable = ctx.getConfig().targetTableName(event.getObjectName());

        switch (event.getChangeType()) {
            case CREATE:
                executor.executeIdempotentCreate(dialect.getAddColumnSql(
                        ctx.getTargetSchema(), targetTable, column, ctx.getSourceType()));
                return ApplyOutcome.ok("Added column " + column.getName() + " to " + targetTable);
            case ALTER:
                if (!dialect.supportsAlterColumnType()) {
                    return ApplyOutcome.skipped("Target dialect cannot alter column types in place");
                }
                executor.execute(dialect.getModifyColumnSql(
                        ctx.getTargetSchema(), targetTable, column, ctx.getSourceType()), false);
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

        if (event.getChangeType() == ChangeType.ALTER) {
            // An index definition cannot be changed in place; drop then recreate.
            executor.execute(dialect.getDropIndexSql(index, ctx.getTargetSchema(), targetTable), true);
        }
        executor.executeIdempotentCreate(
                dialect.getCreateIndexSql(index, ctx.getTargetSchema(), targetTable));
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
            statements = List.of(override);
        } else {
            // Normalize to a bare SELECT, then translate it into the target's dialect.
            ViewMeta translated = new ViewMeta();
            translated.setName(view.getName());
            translated.setSchema(ctx.getTargetSchema());
            String body = bodyConverter.extractViewBody(view.getDefinition());
            translated.setDefinition(bodyConverter.convert(body, ctx.getSourceType(),
                    ctx.getTargetType()));
            statements = dialect.getCreateViewSql(translated, ctx.getTargetSchema(),
                    ctx.getSourceType());
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
            ProcedureMeta translated = new ProcedureMeta();
            translated.setName(proc.getName());
            translated.setSchema(ctx.getTargetSchema());
            translated.setRoutineType(proc.getRoutineType());
            translated.setReturnType(proc.getReturnType());
            translated.setParameters(proc.getParameters());
            translated.setDefinition(bodyConverter.convert(proc.getDefinition(),
                    ctx.getSourceType(), ctx.getTargetType()));
            statements = dialect.getCreateProcedureSql(translated, ctx.getTargetSchema(),
                    ctx.getSourceType());
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

    /** True when the source and target speak the same dialect family. */
    public boolean isSameFamily(DatabaseType source, DatabaseType target) {
        return source != null && target != null && source.getFamily() == target.getFamily();
    }
}
