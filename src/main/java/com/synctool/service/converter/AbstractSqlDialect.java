package com.synctool.service.converter;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.IndexMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared dialect behaviour: identifier quoting, CREATE TABLE assembly, index DDL and
 * JDBC-type-driven type mapping.
 *
 * <p>Type mapping keys off {@link ColumnMeta#getJdbcType()} rather than the product type
 * name wherever possible. The driver has already classified the column into a portable
 * category, which is more reliable than matching a long tail of product-specific spellings.
 * Subclasses only need to name their own types for each category.
 */
@Slf4j
public abstract class AbstractSqlDialect implements SqlDialect {

    @Override
    public String quoteIdentifier(String name) {
        if (name == null) {
            return null;
        }
        char q = quoteChar();
        // Defend against an identifier that already carries quotes.
        String bare = name.replace(String.valueOf(q), "");
        return q + bare + q;
    }

    protected abstract char quoteChar();

    @Override
    public String qualify(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(name);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    @Override
    public String mapType(ColumnMeta column, DatabaseType sourceType) {
        String mapped = mapByJdbcType(column, sourceType);
        if (mapped != null) {
            return mapped;
        }
        // Unclassified type: pass the source name through and let the target complain.
        // This is preferable to guessing, and the failure names the exact column.
        log.warn("No type mapping for column {} (type {} / JDBC {}); passing through as-is",
                column.getName(), column.getTypeName(), column.getJdbcType());
        return column.getTypeName();
    }

    /** Maps by {@link Types} category; returns null when the category is unknown. */
    protected String mapByJdbcType(ColumnMeta c, DatabaseType sourceType) {
        int size = c.getSize() == null ? 0 : c.getSize();
        int scale = c.getDecimalDigits() == null ? 0 : c.getDecimalDigits();

        switch (c.getJdbcType()) {
            case Types.BIT:
            case Types.BOOLEAN:
                return booleanType();
            case Types.TINYINT:
                return tinyIntType();
            case Types.SMALLINT:
                return smallIntType();
            case Types.INTEGER:
                return intType();
            case Types.BIGINT:
                return bigIntType();
            case Types.REAL:
                return floatType();
            case Types.FLOAT:
            case Types.DOUBLE:
                return doubleType();
            case Types.NUMERIC:
            case Types.DECIMAL:
                return decimalType(effectivePrecision(size), scale, c, sourceType);
            case Types.CHAR:
            case Types.NCHAR:
                return charType(Math.max(size, 1));
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return varcharType(size);
            case Types.DATE:
                return dateType();
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return timeType();
            case Types.TIMESTAMP:
                return timestampType();
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return timestampTzType();
            case Types.BINARY:
            case Types.VARBINARY:
                return binaryType(size);
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return blobType();
            case Types.CLOB:
            case Types.NCLOB:
                return clobType();
            default:
                return null;
        }
    }

    /**
     * Clamps an implausible precision. Some drivers report a huge COLUMN_SIZE for
     * unconstrained numerics (Oracle NUMBER without precision reports 0 or 38+), which the
     * target would reject.
     */
    protected int effectivePrecision(int size) {
        if (size <= 0 || size > maxNumericPrecision()) {
            return defaultNumericPrecision();
        }
        return size;
    }

    protected int maxNumericPrecision() {
        return 38;
    }

    protected int defaultNumericPrecision() {
        return 38;
    }

    // --- Type names each dialect must supply -------------------------------------------

    protected abstract String booleanType();

    protected abstract String tinyIntType();

    protected abstract String smallIntType();

    protected abstract String intType();

    protected abstract String bigIntType();

    protected abstract String floatType();

    protected abstract String doubleType();

    protected abstract String dateType();

    protected abstract String timeType();

    protected abstract String timestampType();

    protected String timestampTzType() {
        return timestampType();
    }

    protected abstract String blobType();

    protected abstract String clobType();

    protected String charType(int size) {
        return "CHAR(" + Math.min(size, maxCharLength()) + ")";
    }

    /**
     * A VARCHAR of the given length, promoted to a LOB type when the source length exceeds
     * what this dialect allows inline.
     */
    protected String varcharType(int size) {
        if (size <= 0 || size > maxVarcharLength()) {
            return clobType();
        }
        return "VARCHAR(" + size + ")";
    }

    protected String binaryType(int size) {
        if (size <= 0 || size > maxVarcharLength()) {
            return blobType();
        }
        return "VARBINARY(" + size + ")";
    }

    protected String decimalType(int precision, int scale, ColumnMeta c, DatabaseType sourceType) {
        // Oracle NUMBER with scale 0 and no precision is an integer in practice; using
        // DECIMAL(38,0) everywhere would be correct but needlessly wide.
        if (scale <= 0 && precision <= 0) {
            return bigIntType();
        }
        int p = Math.max(precision, 1);
        int s = Math.max(Math.min(scale, p), 0);
        return "DECIMAL(" + p + "," + s + ")";
    }

    protected int maxVarcharLength() {
        return 65535;
    }

    protected int maxCharLength() {
        return 255;
    }

    // --- DDL assembly -------------------------------------------------------------------

    @Override
    public String getCreateTableSql(TableMeta table, String targetSchema, String targetTable,
                                    DatabaseType sourceType) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualify(targetSchema, targetTable)).append(" (\n");

        List<String> defs = new ArrayList<>();
        for (ColumnMeta col : table.getColumns()) {
            defs.add("  " + columnDefinition(col, sourceType));
        }
        if (table.hasPrimaryKey()) {
            String pkCols = table.getPrimaryKeys().stream()
                    .map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
            defs.add("  PRIMARY KEY (" + pkCols + ")");
        }
        sb.append(String.join(",\n", defs));
        sb.append("\n)");
        sb.append(createTableSuffix(table));
        return sb.toString();
    }

    /** One column's definition inside CREATE TABLE. */
    protected String columnDefinition(ColumnMeta col, DatabaseType sourceType) {
        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(col.getName())).append(' ');

        if (col.isAutoIncrement() && autoIncrementColumnType(col, sourceType) != null) {
            sb.append(autoIncrementColumnType(col, sourceType));
        } else {
            sb.append(mapType(col, sourceType));
        }

        // A primary key column is implicitly NOT NULL; stating it is harmless and clearer.
        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        String def = renderDefaultValue(col);
        if (def != null) {
            sb.append(" DEFAULT ").append(def);
        }

        if (col.isAutoIncrement()) {
            String clause = autoIncrementClause();
            if (clause != null && !clause.isEmpty()) {
                sb.append(' ').append(clause);
            }
        }
        return sb.toString();
    }

    /**
     * A single type token that already implies identity/serial behaviour (e.g. PostgreSQL
     * {@code BIGSERIAL}); null when the dialect uses a separate clause instead.
     */
    protected String autoIncrementColumnType(ColumnMeta col, DatabaseType sourceType) {
        return null;
    }

    /** A trailing clause such as {@code AUTO_INCREMENT} or {@code IDENTITY(1,1)}. */
    protected String autoIncrementClause() {
        return null;
    }

    protected String createTableSuffix(TableMeta table) {
        return "";
    }

    @Override
    public String renderDefaultValue(ColumnMeta column) {
        String def = column.getDefaultValue();
        if (def == null) {
            return null;
        }
        String trimmed = def.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Auto-increment columns carry a sequence default that must not be copied.
        if (column.isAutoIncrement()) {
            return null;
        }
        String upper = trimmed.toUpperCase();
        if (upper.equals("NULL")) {
            return "NULL";
        }
        // Already a literal or a function call the target is likely to understand.
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            return normalizeQuotedDefault(trimmed);
        }
        if (isCurrentTimestampDefault(upper)) {
            return currentTimestampFunction();
        }
        if (isNumeric(trimmed)) {
            return trimmed;
        }
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return renderBooleanDefault(upper.equals("TRUE"));
        }
        // A product-specific expression: skip rather than emit something the target rejects.
        // Losing a default is recoverable; a failed CREATE TABLE blocks the whole table.
        log.debug("Skipping non-portable default for column {}: {}", column.getName(), trimmed);
        return null;
    }

    /** Converts a double-quoted default (PostgreSQL style) into single quotes. */
    protected String normalizeQuotedDefault(String value) {
        String v = value;
        // PostgreSQL appends a cast, e.g. 'active'::character varying.
        int cast = v.indexOf("::");
        if (cast > 0) {
            v = v.substring(0, cast);
        }
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = "'" + v.substring(1, v.length() - 1).replace("'", "''") + "'";
        }
        return v;
    }

    protected boolean isCurrentTimestampDefault(String upper) {
        return upper.startsWith("CURRENT_TIMESTAMP")
                || upper.startsWith("NOW()")
                || upper.startsWith("GETDATE()")
                || upper.startsWith("SYSDATE")
                || upper.startsWith("LOCALTIMESTAMP")
                || upper.startsWith("SYSTIMESTAMP");
    }

    protected String currentTimestampFunction() {
        return "CURRENT_TIMESTAMP";
    }

    protected String renderBooleanDefault(boolean value) {
        return value ? "1" : "0";
    }

    protected boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType) {
        return "ALTER TABLE " + qualify(schema, table) + " ADD COLUMN "
                + columnDefinition(column, sourceType);
    }

    @Override
    public String getDropColumnSql(String schema, String table, String columnName) {
        return "ALTER TABLE " + qualify(schema, table) + " DROP COLUMN " + quoteIdentifier(columnName);
    }

    @Override
    public String getCreateIndexSql(IndexMeta index, String schema, String table) {
        String cols = index.getColumns().stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        return "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX "
                + quoteIdentifier(indexNameFor(index, table)) + " ON "
                + qualify(schema, table) + " (" + cols + ")";
    }

    /**
     * Index names are schema-scoped in some products and table-scoped in others. Prefixing
     * with the table keeps names unique when the target scopes them per schema.
     */
    protected String indexNameFor(IndexMeta index, String table) {
        String name = index.getName();
        if (!indexNamesAreGlobal()) {
            return truncateIdentifier(name);
        }
        if (name.toLowerCase().contains(table.toLowerCase())) {
            return truncateIdentifier(name);
        }
        return truncateIdentifier(table + "_" + name);
    }

    /** True when index names must be unique across the schema, not just the table. */
    protected boolean indexNamesAreGlobal() {
        return false;
    }

    protected String truncateIdentifier(String name) {
        int max = maxIdentifierLength();
        if (name == null || name.length() <= max) {
            return name;
        }
        // Keep a hash suffix so two truncated names do not collide.
        String hash = Integer.toHexString(name.hashCode());
        return name.substring(0, max - hash.length() - 1) + "_" + hash;
    }

    protected int maxIdentifierLength() {
        return 63;
    }

    @Override
    public String getDropIndexSql(IndexMeta index, String schema, String table) {
        return "DROP INDEX " + quoteIdentifier(indexNameFor(index, table));
    }

    @Override
    public String getDropTableSql(String schema, String table) {
        return "DROP TABLE " + qualify(schema, table);
    }

    @Override
    public String getDropViewSql(String schema, String view) {
        return "DROP VIEW " + qualify(schema, view);
    }

    @Override
    public String getDropProcedureSql(String schema, String procedure, boolean isFunction) {
        return "DROP " + (isFunction ? "FUNCTION " : "PROCEDURE ") + qualify(schema, procedure);
    }

    @Override
    public String getDeleteByPkSql(String schema, String table, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("delete.requires.pk");
        }
        String where = pkColumns.stream()
                .map(c -> quoteIdentifier(c) + " = ?")
                .collect(Collectors.joining(" AND "));
        return "DELETE FROM " + qualify(schema, table) + " WHERE " + where;
    }

    @Override
    public String getTruncateSql(String schema, String table) {
        return "TRUNCATE TABLE " + qualify(schema, table);
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        // SQL:2008 form, supported by most modern products.
        return baseSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String getDisableConstraintsSql() {
        return null;
    }

    @Override
    public String getEnableConstraintsSql() {
        return null;
    }

    @Override
    public boolean supportsAlterColumnType() {
        return true;
    }

    /** Default bind order: one value per column, in the order given. */
    @Override
    public List<String> upsertBindOrder(List<String> columns, List<String> pkColumns) {
        return new ArrayList<>(columns);
    }

    /** Non-key columns, i.e. the ones an upsert should overwrite. */
    protected List<String> nonKeyColumns(List<String> columns, List<String> pkColumns) {
        List<String> pkUpper = pkColumns.stream().map(String::toUpperCase).collect(Collectors.toList());
        return columns.stream()
                .filter(c -> !pkUpper.contains(c.toUpperCase()))
                .collect(Collectors.toList());
    }

    protected String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    protected String quotedList(List<String> names) {
        return names.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
    }
}
