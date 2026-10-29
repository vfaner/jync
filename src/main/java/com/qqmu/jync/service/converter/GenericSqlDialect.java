package com.qqmu.jync.service.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * ANSI-leaning dialect used for {@link DatabaseType#CUSTOM} and any product without a
 * dedicated implementation.
 *
 * <p>It emits only standard SQL. Notably it cannot produce a single-statement upsert, so
 * {@link #getUpsertSql} returns a plain INSERT and the sync engine falls back to an
 * update-then-insert sequence to preserve idempotency. That fallback is slower but correct
 * on any product that speaks SQL-92.
 */
@Component
public class GenericSqlDialect extends AbstractSqlDialect {

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.GENERIC;
    }

    @Override
    protected char quoteChar() {
        return '"';
    }

    @Override
    protected String booleanType() {
        return "SMALLINT";
    }

    @Override
    protected String tinyIntType() {
        return "SMALLINT";
    }

    @Override
    protected String smallIntType() {
        return "SMALLINT";
    }

    @Override
    protected String intType() {
        return "INTEGER";
    }

    @Override
    protected String bigIntType() {
        return "BIGINT";
    }

    @Override
    protected String floatType() {
        return "REAL";
    }

    @Override
    protected String doubleType() {
        return "DOUBLE PRECISION";
    }

    @Override
    protected String dateType() {
        return "DATE";
    }

    @Override
    protected String timeType() {
        return "TIME";
    }

    @Override
    protected String timestampType() {
        return "TIMESTAMP";
    }

    @Override
    protected String blobType() {
        return "BLOB";
    }

    @Override
    protected String clobType() {
        return "CLOB";
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        return "ALTER TABLE " + qualify(schema, table) + " ALTER COLUMN "
                + quoteIdentifier(column.getName()) + " " + mapType(column, sourceType);
    }

    @Override
    public List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType) {
        // Standard SQL has no CREATE OR REPLACE VIEW, so replace via drop-then-create. The
        // executor tolerates the drop failing when the view does not exist yet, which keeps
        // the pair safe to re-run.
        return List.of(
                "DROP VIEW " + qualify(schema, view.getName()),
                "CREATE VIEW " + qualify(schema, view.getName()) + " AS " + stripSemicolon(view.getDefinition()));
    }

    private String stripSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema,
                                              DatabaseType sourceType) {
        // Routine syntax is too product-specific to synthesize blindly. Emitting the source
        // verbatim at least gives the target a chance and surfaces a precise error if not.
        String body = procedure.getDefinition();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return List.of(body.trim());
    }

    /**
     * No portable single-statement upsert exists, so this returns a plain INSERT. The engine
     * detects the missing upsert support via {@link #supportsNativeUpsert()} and switches to
     * update-then-insert.
     */
    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        return "INSERT INTO " + qualify(schema, table) + " (" + quotedList(columns)
                + ") VALUES (" + placeholders(columns.size()) + ")";
    }

    /** Update statement used by the update-then-insert fallback. */
    public String getUpdateByPkSql(String schema, String table, List<String> columns,
                                   List<String> pkColumns) {
        return getUpdateByPkSql(schema, table, columns, pkColumns, false);
    }

    /**
     * Update statement used by the update-then-insert fallback.
     *
     * <p>With {@code guardUnchanged} the statement also requires at least one non-key column to
     * actually differ, so a row already holding these exact values reports zero affected rows
     * instead of being counted as an update. The engine needs that on incremental windows: the
     * cursor's safety margin replays the newest rows every cycle, and without the condition
     * every replay became a fresh "1 row updated" in the change log. The condition is NULL-safe
     * for the same reason {@link UpsertChangeGuard} is: a value appearing or disappearing must
     * count as a difference, not vanish into SQL's three-valued logic.
     *
     * <p>The caller then binds, in order: the SET values, the key, then this condition's
     * placeholders (three per non-key column, since the same incoming value is compared three
     * times) — the key stands first in the WHERE clause.
     */
    public String getUpdateByPkSql(String schema, String table, List<String> columns,
                                   List<String> pkColumns, boolean guardUnchanged) {
        List<String> updatable = nonKeyColumns(columns, pkColumns);
        if (updatable.isEmpty()) {
            return null;
        }
        String sets = updatable.stream()
                .map(c -> quoteIdentifier(c) + " = ?")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String where = pkColumns.stream()
                .map(c -> quoteIdentifier(c) + " = ?")
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();
        if (guardUnchanged) {
            String guard = updatable.stream()
                    .map(this::differsFromBoundValue)
                    .reduce((a, b) -> a + " OR " + b)
                    .orElseThrow();
            where = where + " AND (" + guard + ")";
        }
        return "UPDATE " + qualify(schema, table) + " SET " + sets + " WHERE " + where;
    }

    /** NULL-safe "this column differs from the incoming value" over the next three placeholders. */
    private String differsFromBoundValue(String column) {
        String ref = quoteIdentifier(column);
        return "(" + ref + " <> ? OR (" + ref + " IS NULL AND ? IS NOT NULL)"
                + " OR (" + ref + " IS NOT NULL AND ? IS NULL))";
    }

    /**
     * Existence probe by primary key, used by the update-then-insert fallback when an INSERT
     * is rejected with an integrity violation: the caller must know whether the key already
     * holds a row (a genuine re-delivery to absorb) or whether some OTHER constraint rejected
     * the row (a failure that must surface).
     */
    public String getSelectByPkSql(String schema, String table, List<String> pkColumns) {
        String where = pkColumns.stream()
                .map(c -> quoteIdentifier(c) + " = ?")
                .reduce((a, b) -> a + " AND " + b)
                .orElseThrow();
        return "SELECT 1 FROM " + qualify(schema, table) + " WHERE " + where;
    }

    /** Signals that the engine must emulate an upsert rather than trust one statement. */
    public boolean supportsNativeUpsert() {
        return false;
    }
}
