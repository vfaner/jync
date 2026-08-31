package com.synctool.service.converter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

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
        return "UPDATE " + qualify(schema, table) + " SET " + sets + " WHERE " + where;
    }

    @Override
    public boolean supportsAlterColumnType() {
        // Unknown; assume yes and let the target reject it with a precise message.
        return true;
    }

    /** Signals that the engine must emulate an upsert rather than trust one statement. */
    public boolean supportsNativeUpsert() {
        return false;
    }
}
