package com.synctool.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

/** PostgreSQL dialect, shared with OpenGauss, 人大金仓 (KingBase) and 神通 (Oscar). */
@Component
public class PostgresDialect extends AbstractSqlDialect {

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.POSTGRES;
    }

    @Override
    protected char quoteChar() {
        return '"';
    }

    @Override
    protected String booleanType() {
        return "BOOLEAN";
    }

    @Override
    protected String tinyIntType() {
        // PostgreSQL has no 1-byte integer.
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
    protected String timestampTzType() {
        return "TIMESTAMP WITH TIME ZONE";
    }

    @Override
    protected String blobType() {
        return "BYTEA";
    }

    @Override
    protected String clobType() {
        return "TEXT";
    }

    @Override
    protected String binaryType(int size) {
        // BYTEA is unbounded; there is no sized binary type.
        return "BYTEA";
    }

    @Override
    protected String varcharType(int size) {
        if (size <= 0 || size > 10485760) {
            return "TEXT";
        }
        return "VARCHAR(" + size + ")";
    }

    @Override
    protected int maxIdentifierLength() {
        return 63;
    }

    @Override
    protected boolean indexNamesAreGlobal() {
        // Index names live in the schema namespace alongside tables.
        return true;
    }

    @Override
    protected String renderBooleanDefault(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    /** SERIAL/BIGSERIAL create the sequence and default in one token. */
    @Override
    protected String autoIncrementColumnType(ColumnMeta col, DatabaseType sourceType) {
        String base = mapType(col, sourceType);
        if ("BIGINT".equals(base)) {
            return "BIGSERIAL";
        }
        if ("SMALLINT".equals(base)) {
            return "SMALLSERIAL";
        }
        return "SERIAL";
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        // PostgreSQL needs one ALTER per aspect; USING makes an incompatible cast explicit.
        return "ALTER TABLE " + qualify(schema, table) + " ALTER COLUMN "
                + quoteIdentifier(column.getName()) + " TYPE " + mapType(column, sourceType)
                + " USING " + quoteIdentifier(column.getName()) + "::"
                + mapType(column, sourceType);
    }

    @Override
    public List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType) {
        return List.of("CREATE OR REPLACE VIEW " + qualify(schema, view.getName())
                + " AS " + stripSemicolon(view.getDefinition()));
    }

    @Override
    public List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema,
                                              DatabaseType sourceType) {
        String body = procedure.getDefinition();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String trimmed = body.trim();
        List<String> stmts = new ArrayList<>();
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("CREATE OR REPLACE")) {
            stmts.add(trimmed);
        } else if (upper.startsWith("CREATE")) {
            stmts.add(trimmed.replaceFirst("(?i)^CREATE\\s+", "CREATE OR REPLACE "));
        } else {
            // A bare body from prosrc: wrap it in a plpgsql function definition.
            stmts.add(buildWrapper(procedure, schema, trimmed));
        }
        return stmts;
    }

    private String buildWrapper(ProcedureMeta proc, String schema, String body) {
        String params = proc.getParameters().stream()
                .filter(p -> !"RETURN".equalsIgnoreCase(p.getMode()))
                .map(p -> p.getMode() + " " + quoteIdentifier(p.getName()) + " " + p.getTypeName())
                .collect(Collectors.joining(", "));
        boolean isFunction = proc.isFunction();
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE ")
                .append(isFunction ? "FUNCTION " : "PROCEDURE ")
                .append(qualify(schema, proc.getName()))
                .append('(').append(params).append(')');
        if (isFunction) {
            sb.append(" RETURNS ")
                    .append(proc.getReturnType() == null ? "void" : proc.getReturnType());
        }
        // Dollar quoting avoids escaping anything inside the body.
        sb.append(" LANGUAGE plpgsql AS $synctool$\n").append(body).append("\n$synctool$");
        return sb.toString();
    }

    private String stripSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * {@code INSERT ... ON CONFLICT DO UPDATE} — idempotent, and the conflict target names
     * the primary key explicitly so the intent is unambiguous.
     */
    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        String insert = "INSERT INTO " + qualify(schema, table)
                + " (" + quotedList(columns) + ") VALUES (" + placeholders(columns.size()) + ")";
        if (pkColumns == null || pkColumns.isEmpty()) {
            return insert;
        }
        String conflict = " ON CONFLICT (" + quotedList(pkColumns) + ")";
        List<String> updatable = nonKeyColumns(columns, pkColumns);
        if (updatable.isEmpty()) {
            return insert + conflict + " DO NOTHING";
        }
        String updates = updatable.stream()
                .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        return insert + conflict + " DO UPDATE SET " + updates;
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        return baseSql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String getTruncateSql(String schema, String table) {
        // CASCADE so a referenced table can still be truncated before a full reload.
        return "TRUNCATE TABLE " + qualify(schema, table) + " CASCADE";
    }

    @Override
    public String getDropIndexSql(com.synctool.dto.meta.IndexMeta index, String schema, String table) {
        return "DROP INDEX " + qualify(schema, indexNameFor(index, table));
    }

    @Override
    public String getDropProcedureSql(String schema, String procedure, boolean isFunction) {
        // PostgreSQL routines are overloadable, so the name alone can be ambiguous;
        // CASCADE-free DROP with IF EXISTS is the safest generic form.
        return "DROP " + (isFunction ? "FUNCTION" : "PROCEDURE") + " IF EXISTS "
                + qualify(schema, procedure);
    }
}
