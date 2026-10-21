package com.qqmu.jync.service.converter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/** MySQL / MariaDB dialect. */
@Slf4j
@Component
public class MySqlDialect extends AbstractSqlDialect {

    /** MySQL rejects a longer column comment (error 1629). */
    private static final int MAX_COMMENT_LENGTH = 1024;

    /** MySQL rejects a longer table comment (error 1629). */
    private static final int MAX_TABLE_COMMENT_LENGTH = 2048;

    /** InnoDB's 65535-byte row limit minus room for the row header and length bytes. */
    private static final long ROW_SIZE_BUDGET = 60000L;

    /**
     * In-row cost of an off-page TEXT/BLOB value in InnoDB's default DYNAMIC row format:
     * a 20-byte pointer. (COMPACT would need 9-12; charging the larger value keeps the
     * estimate safe on both.)
     */
    private static final long LOB_ROW_BYTES = 20L;

    /**
     * Characters of a LONGTEXT column an index covers. InnoDB's default row format allows
     * 3072-byte keys (768 utf8mb4 characters), but 767 bytes / 191 characters is the only
     * limit every supported server version and row format accepts.
     */
    private static final int INDEX_PREFIX_CHARS = 191;

    /**
     * Which columns a narrowing pass may take, cheapest sacrifice first: unindexed columns,
     * then columns of ordinary indexes (their index survives as a prefix index), then
     * UNIQUE columns (their index is skipped).
     */
    private static final int PASS_UNINDEXED = 1;
    private static final int PASS_INDEXED = 2;
    private static final int PASS_UNIQUE = 3;

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.MYSQL;
    }

    /** Table and column names; MariaDB and the MySQL-compatible products share the limit. */
    @Override
    public int maxIdentifierBytes() {
        return 64;
    }

    /** MySQL documents 64 <em>characters</em>, not bytes. */
    @Override
    public boolean identifierLimitInBytes() {
        return false;
    }

    /**
     * MySQL Connector/J — and the MySQL-protocol forks this family covers (MariaDB,
     * OceanBase, TiDB, GBase) — only stream a result set row-by-row when the fetch size
     * is {@code Integer.MIN_VALUE}; any other value silently buffers the whole set in
     * the client, which a multi-million-row full load would not survive.
     *
     * <p>Streaming requires a forward-only, read-only statement (the default here) and
     * blocks the connection for other statements until the result set closes — safe in
     * the sync path, where the source connection runs exactly one SELECT at a time and
     * every write goes to a different connection.
     */
    @Override
    public int streamingFetchSize(int configured) {
        return Integer.MIN_VALUE;
    }

    @Override
    protected char quoteChar() {
        return '`';
    }

    /**
     * MySQL refuses a DEFAULT on TEXT/BLOB/JSON/GEOMETRY columns (error 1101; 8.0 accepts an
     * expression default only). The row-size plan turns wide VARCHARs into LONGTEXT, so a
     * source default on such a column must be dropped rather than copied — otherwise the
     * CREATE TABLE fails outright.
     */
    @Override
    protected boolean defaultClauseAllowed(String targetType) {
        if (targetType == null) {
            return true;
        }
        String type = targetType.toUpperCase(Locale.ROOT);
        return !(type.contains("TEXT") || type.contains("BLOB") || type.contains("JSON")
                || type.contains("GEOMETRY"));
    }

    @Override
    protected String booleanType() {
        return "TINYINT(1)";
    }

    @Override
    protected String tinyIntType() {
        return "TINYINT";
    }

    @Override
    protected String smallIntType() {
        return "SMALLINT";
    }

    @Override
    protected String intType() {
        return "INT";
    }

    @Override
    protected String bigIntType() {
        return "BIGINT";
    }

    @Override
    protected String unsignedBigIntType() {
        // Native unsigned type holds 2^64-1 directly; no need to widen to DECIMAL.
        return "BIGINT UNSIGNED";
    }

    @Override
    protected String floatType() {
        return "FLOAT";
    }

    @Override
    protected String doubleType() {
        return "DOUBLE";
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
        // DATETIME(6), not DATETIME: scale 0 silently truncates fractional seconds on
        // every synced row, and a truncated cursor column skews the row-count audit's
        // "<= watermark" comparison. MySQL 5.6+ and MariaDB 10+ both support (6).
        return "DATETIME(6)";
    }

    /**
     * MySQL requires a temporal default's fractional precision to match the column's:
     * {@code DATETIME(6) DEFAULT CURRENT_TIMESTAMP} is rejected with "Invalid default
     * value" (error 1067), {@code CURRENT_TIMESTAMP(6)} is accepted. {@link #timestampType()}
     * always maps to DATETIME(6), so the default must always carry the (6).
     */
    @Override
    protected String currentTimestampFunction() {
        return "CURRENT_TIMESTAMP(6)";
    }

    @Override
    protected String blobType() {
        return "LONGBLOB";
    }

    @Override
    protected String clobType() {
        return "LONGTEXT";
    }

    @Override
    protected String jsonType() {
        // Native JSON: PG json/jsonb text is valid JSON input.
        return "JSON";
    }

    @Override
    protected int maxVarcharLength() {
        // The real ceiling depends on row size and charset; 16383 is safe for utf8mb4.
        return 16383;
    }

    @Override
    protected int maxIdentifierLength() {
        return 64;
    }

    @Override
    protected String autoIncrementClause() {
        return "AUTO_INCREMENT";
    }

    /**
     * MySQL rejects a table whose AUTO_INCREMENT column is not any kind of key
     * (ER_WRONG_AUTO_KEY, 1075). A source identity/serial column without a primary key
     * (legal on PostgreSQL, Oracle and DB2) therefore needs an explicit UNIQUE KEY.
     */
    @Override
    protected String additionalTableConstraint(TableMeta table, String targetTable) {
        ColumnMeta ai = autoIncrementColumnWithoutKey(table);
        if (ai == null) {
            return null;
        }
        return "UNIQUE KEY " + quoteIdentifier(autoKeyName(targetTable, ai.getName()))
                + " (" + quoteIdentifier(ai.getName()) + ")";
    }

    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType) {
        return getAddColumnSql(schema, table, column, sourceType, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The narrowing plan is recomputed for the whole table: a column the CREATE TABLE
     * narrowed to LONGTEXT must be added that way too, and a wide column arriving on a
     * table that already sits at the row-size budget may itself have to be narrowed.
     */
    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType, TableMeta sourceTable) {
        // The unique key must be part of the SAME ALTER statement: MySQL rejects a standalone
        // ADD COLUMN of an AUTO_INCREMENT column without a key (ER_WRONG_AUTO_KEY, 1075), so a
        // follow-up companion statement would arrive too late.
        String sql = "ALTER TABLE " + qualify(schema, table) + " ADD COLUMN "
                + columnDefinition(column, sourceType, narrowedType(sourceTable, sourceType,
                        column));
        if (column.isAutoIncrement() && !column.isPrimaryKey()) {
            sql += ", ADD UNIQUE KEY " + quoteIdentifier(autoKeyName(table, column.getName()))
                    + " (" + quoteIdentifier(column.getName()) + ")";
        }
        return sql;
    }

    private static ColumnMeta autoIncrementColumnWithoutKey(TableMeta table) {
        if (table.getColumns() == null) {
            return null;
        }
        List<String> pk = table.getPrimaryKeys() == null ? List.of()
                : table.getPrimaryKeys().stream()
                        .map(n -> n == null ? null : n.toUpperCase(java.util.Locale.ROOT))
                        .collect(Collectors.toList());
        for (ColumnMeta col : table.getColumns()) {
            if (col.isAutoIncrement()
                    && (col.getName() == null
                        || !pk.contains(col.getName().toUpperCase(java.util.Locale.ROOT)))) {
                return col;
            }
        }
        return null;
    }

    private String autoKeyName(String table, String column) {
        return truncateIdentifier("uk_" + table + "_" + column);
    }

    @Override
    protected String createTableSuffix(TableMeta table) {
        StringBuilder sb = new StringBuilder(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        if (table.getRemarks() != null && !table.getRemarks().isBlank()) {
            sb.append(" COMMENT='").append(escape(tableComment(table.getRemarks()))).append('\'');
        }
        return sb.toString();
    }

    /**
     * Updates the comment of a table that already exists. MySQL has no {@code COMMENT ON}
     * statement, but {@code ALTER TABLE ... COMMENT = '...'} is a metadata-only change.
     * A null/blank comment clears it, so a comment removed at the source does not linger.
     */
    @Override
    public String getAlterTableCommentSql(String schema, String table, String comment) {
        return "ALTER TABLE " + qualify(schema, table) + " COMMENT='"
                + escape(tableComment(comment)) + "'";
    }

    /** MySQL rejects table comments longer than 2048 characters (error 1629). */
    private static String tableComment(String comment) {
        if (comment == null) {
            return "";
        }
        return comment.length() > MAX_TABLE_COMMENT_LENGTH
                ? comment.substring(0, MAX_TABLE_COMMENT_LENGTH) : comment;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    /**
     * MySQL stores a column comment inline in the column definition, so it travels with the
     * statement that creates or alters the column. Comments longer than 1024 characters are
     * rejected (error 1629), hence the truncation.
     */
    @Override
    protected String columnCommentClause(ColumnMeta col) {
        String remarks = col.getRemarks();
        if (remarks == null || remarks.isBlank()) {
            return "";
        }
        String text = remarks.length() > MAX_COMMENT_LENGTH
                ? remarks.substring(0, MAX_COMMENT_LENGTH) : remarks;
        return " COMMENT '" + escape(text) + "'";
    }

    /**
     * InnoDB's row limit is 65535 bytes for CHAR/VARCHAR columns, and utf8mb4 charges four
     * bytes per character. An Oracle table with dozens of {@code VARCHAR2(4000)} columns —
     * ordinary in Chinese enterprise schemas — maps to far more than that, and CREATE TABLE
     * fails with ER_TOO_BIG_ROWSIZE (1118), taking the whole table with it.
     *
     * <p>Columns that cannot fit are narrowed to LONGTEXT, which costs a 20-byte pointer in
     * the row regardless of content. Widest columns go first (fewest conversions, least
     * change), and the order is data first, indexes second: columns no index depends on,
     * then ordinary-index columns (their index becomes a prefix index), then UNIQUE columns.
     * A UNIQUE column is narrowed only when nothing else can make the row fit — its index
     * is then skipped rather than weakened, because prefix uniqueness would reject rows the
     * source accepts. Primary-key columns are never narrowed at all: MySQL cannot put TEXT
     * in a PRIMARY KEY. The 60000 budget leaves headroom for the row header, null bitmap
     * and length bytes.
     */
    @Override
    protected Map<String, String> createColumnTypeOverrides(TableMeta table,
                                                            DatabaseType sourceType) {
        return narrowedColumns(table, sourceType, true);
    }

    /**
     * @param report whether to log the narrowing and the "still too wide" diagnosis. Index
     *               DDL recomputes the same plan once per index and must stay quiet: the
     *               table's own CREATE (or its absence from this cycle) already reported it.
     */
    private Map<String, String> narrowedColumns(TableMeta table, DatabaseType sourceType,
                                                boolean report) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            return Map.of();
        }
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Long> bytes = new LinkedHashMap<>();
        long total = 0;
        for (ColumnMeta col : table.getColumns()) {
            String type = mappedType(col, sourceType);
            types.put(keyOf(col), type);
            long size = estimateRowBytes(type);
            bytes.put(keyOf(col), size);
            total += size;
        }
        if (total <= ROW_SIZE_BUDGET) {
            return Map.of();
        }

        Set<String> indexed = indexedColumns(table);
        Set<String> uniqueKeys = uniqueKeyColumns(table);
        Set<String> primaryKeys = primaryKeyColumns(table);
        List<ColumnMeta> candidates = new ArrayList<>();
        for (ColumnMeta col : table.getColumns()) {
            // A primary-key column can never become LONGTEXT: MySQL cannot put a TEXT
            // column in a PRIMARY KEY, so converting one would turn the row-size error into
            // an "incorrect table definition" error. Leave keys alone and narrow the rest.
            if (!col.isAutoIncrement() && !primaryKeys.contains(keyOf(col))
                    && isCharType(types.get(keyOf(col)))) {
                candidates.add(col);
            }
        }
        candidates.sort(Comparator.comparingLong(
                (ColumnMeta c) -> bytes.get(keyOf(c))).reversed());

        long originalTotal = total;
        Map<String, String> overrides = new LinkedHashMap<>();
        List<String> converted = new ArrayList<>();
        // Data first, indexes last: widening a column costs its index, so columns no index
        // depends on go first, then ordinary indexes, and UNIQUE columns only when the row
        // cannot be made to fit any other way.
        for (int pass = PASS_UNINDEXED; pass <= PASS_UNIQUE; pass++) {
            total = convertWithinBudget(candidates, pass, indexed, uniqueKeys, types, bytes,
                    total, overrides, converted);
        }

        if (report && !converted.isEmpty()) {
            log.warn("Table {} needs {} bytes per row on MySQL (utf8mb4 limit ~65535); "
                            + "converted {} column(s) to LONGTEXT, row now ~{} bytes: {}",
                    table.getName(), originalTotal, converted.size(), total,
                    String.join(", ", converted));
        }
        if (report && total > ROW_SIZE_BUDGET) {
            // Everything convertible has been converted and the row still does not fit. The
            // remaining width is in keys or fixed-size types, which cannot be narrowed
            // without changing the table's meaning; say so once instead of letting MySQL
            // answer with a bare ER_TOO_BIG_ROWSIZE.
            log.error("Table {} still needs ~{} bytes per row after narrowing what is safe"
                            + " (MySQL limit ~65535); CREATE TABLE is likely to fail with"
                            + " ER_TOO_BIG_ROWSIZE (1118). Review the key columns or shorten"
                            + " the widest source columns.",
                    table.getName(), total);
        }
        return overrides;
    }

    private long convertWithinBudget(List<ColumnMeta> candidates, int pass,
                                     Set<String> indexed, Set<String> uniqueKeys,
                                     Map<String, String> types, Map<String, Long> bytes,
                                     long total, Map<String, String> overrides,
                                     List<String> converted) {
        for (ColumnMeta col : candidates) {
            if (total <= ROW_SIZE_BUDGET) {
                break;
            }
            String key = keyOf(col);
            int columnPass = !indexed.contains(key) ? PASS_UNINDEXED
                    : (uniqueKeys.contains(key) ? PASS_UNIQUE : PASS_INDEXED);
            if (pass != columnPass || overrides.containsKey(key)) {
                continue;
            }
            total -= bytes.get(key);
            total += LOB_ROW_BYTES;
            overrides.put(key, clobType());
            converted.add(col.getName() + " (" + types.get(key) + ")");
        }
        return total;
    }

    private String mappedType(ColumnMeta col, DatabaseType sourceType) {
        if (col.isAutoIncrement() && autoIncrementColumnType(col, sourceType) != null) {
            return autoIncrementColumnType(col, sourceType);
        }
        return mapType(col, sourceType);
    }

    private static Set<String> primaryKeyColumns(TableMeta table) {
        Set<String> names = new HashSet<>();
        if (table.getPrimaryKeys() != null) {
            for (String pk : table.getPrimaryKeys()) {
                if (pk != null) {
                    names.add(pk.toUpperCase(Locale.ROOT));
                }
            }
        }
        for (ColumnMeta col : table.getColumns()) {
            if (col.isPrimaryKey() && col.getName() != null) {
                names.add(keyOf(col));
            }
        }
        return names;
    }

    /** Columns any primary-key or UNIQUE index covers; narrowing them costs an index. */
    private static Set<String> uniqueKeyColumns(TableMeta table) {
        Set<String> names = new HashSet<>(primaryKeyColumns(table));
        if (table.getIndexes() != null) {
            for (IndexMeta index : table.getIndexes()) {
                if (index.getColumns() == null || (!index.isUnique() && !index.isPrimaryKey())) {
                    continue;
                }
                for (String column : index.getColumns()) {
                    if (column != null) {
                        names.add(column.toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    private static Set<String> indexedColumns(TableMeta table) {
        Set<String> names = new HashSet<>(primaryKeyColumns(table));
        if (table.getIndexes() != null) {
            for (IndexMeta index : table.getIndexes()) {
                if (index.getColumns() == null) {
                    continue;
                }
                for (String column : index.getColumns()) {
                    if (column != null) {
                        names.add(column.toUpperCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    private static final Pattern CHAR_TYPE =
            Pattern.compile("^(?:VAR)?CHAR\\((\\d+)\\)$", Pattern.CASE_INSENSITIVE);

    private static boolean isCharType(String type) {
        return type != null && CHAR_TYPE.matcher(type.trim()).matches();
    }

    /**
     * Worst-case bytes this mapped type contributes to InnoDB's row size. utf8mb4 charges
     * four bytes per character; VARCHAR and CHAR count in full (the row limit is about the
     * declared maximum, not the stored value). TEXT/BLOB/JSON keep only a pointer in the row.
     */
    private static long estimateRowBytes(String type) {
        if (type == null) {
            return 32;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT);
        Matcher charType = CHAR_TYPE.matcher(upper);
        if (charType.matches()) {
            return Long.parseLong(charType.group(1)) * 4L + 2;
        }
        if (upper.startsWith("VARBINARY(") || upper.startsWith("BINARY(")) {
            int open = upper.indexOf('(');
            int close = upper.indexOf(')', open);
            try {
                return Long.parseLong(upper.substring(open + 1, close)) + 2;
            } catch (RuntimeException e) {
                return 32;
            }
        }
        if (upper.contains("TEXT") || upper.contains("BLOB") || upper.equals("JSON")) {
            return LOB_ROW_BYTES;
        }
        if (upper.startsWith("DECIMAL") || upper.startsWith("NUMERIC")) {
            return 32;
        }
        return 16;
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        return getModifyColumnSql(schema, table, column, sourceType, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Without the table's narrowing plan a comment edit would reissue the column's wide
     * source type — widening a LONGTEXT column and pushing the table back over MySQL's
     * row-size limit.
     */
    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType, TableMeta sourceTable) {
        return "ALTER TABLE " + qualify(schema, table) + " MODIFY COLUMN "
                + columnDefinition(column, sourceType, narrowedType(sourceTable, sourceType,
                        column));
    }

    /** The LONGTEXT override for this column, or null when the plan leaves it as mapped. */
    private String narrowedType(TableMeta sourceTable, DatabaseType sourceType,
                                ColumnMeta column) {
        if (sourceTable == null) {
            return null;
        }
        return narrowedColumns(sourceTable, sourceType, false).get(keyOf(column));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A column this table had narrowed to LONGTEXT cannot be indexed without a key
     * length (error 1170), so the index is built as a prefix index over the first
     * {@value #INDEX_PREFIX_CHARS} characters. That keeps the index usable for lookups on
     * narrow values, but it no longer covers the whole column or answers range scans over
     * it, so the divergence is logged.
     *
     * <p>A UNIQUE index whose column was narrowed returns {@code null} instead: a prefix
     * would enforce uniqueness on the first characters only and reject rows the source
     * accepts. Data sync matters more than the index, so the caller skips it.
     */
    @Override
    public String getCreateIndexSql(IndexMeta index, String schema, String table,
                                    TableMeta sourceTable, DatabaseType sourceType) {
        if (sourceTable == null) {
            return super.getCreateIndexSql(index, schema, table);
        }
        Map<String, String> narrowed = narrowedColumns(sourceTable, sourceType, false);
        if (narrowed.isEmpty()) {
            return super.getCreateIndexSql(index, schema, table);
        }

        List<String> columns = new ArrayList<>();
        List<String> prefixed = new ArrayList<>();
        for (String column : index.getColumns()) {
            if (column != null && narrowed.containsKey(column.toUpperCase(Locale.ROOT))) {
                columns.add(quoteIdentifier(column) + "(" + INDEX_PREFIX_CHARS + ")");
                prefixed.add(column);
            } else {
                columns.add(quoteIdentifier(column));
            }
        }
        if (index.isUnique()) {
            if (prefixed.isEmpty()) {
                return super.getCreateIndexSql(index, schema, table);
            }
            log.warn("NOT creating unique index {} on {}: column(s) {} had to be widened to "
                            + "LONGTEXT to fit MySQL's row limit and cannot carry a unique key. "
                            + "Data still syncs; the target has no uniqueness enforcement for it.",
                    index.getName(), table, String.join(", ", prefixed));
            return null;
        }
        if (!prefixed.isEmpty()) {
            log.warn("Index {} on {} covers column(s) {} that were narrowed to LONGTEXT; "
                            + "created as a prefix index over the first {} characters. The index "
                            + "still serves lookups but does not cover the full column, unlike "
                            + "the source index.",
                    index.getName(), table, String.join(", ", prefixed), INDEX_PREFIX_CHARS);
        }
        return "CREATE INDEX " + quoteIdentifier(indexNameFor(index, table)) + " ON "
                + qualify(schema, table) + " (" + String.join(", ", columns) + ")";
    }

    @Override
    public String getDropIndexSql(IndexMeta index, String schema, String table) {
        // MySQL scopes index names to the table, so the table must be named.
        return "DROP INDEX " + quoteIdentifier(indexNameFor(index, table))
                + " ON " + qualify(schema, table);
    }

    @Override
    public List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType) {
        List<String> stmts = new ArrayList<>();
        stmts.add("CREATE OR REPLACE VIEW " + qualify(schema, view.getName())
                + " AS " + view.getDefinition());
        return stmts;
    }

    @Override
    public List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema,
                                              DatabaseType sourceType) {
        List<String> stmts = new ArrayList<>();
        boolean isFunction = procedure.isFunction();
        // MySQL has no CREATE OR REPLACE for routines, so drop first. IF EXISTS keeps the
        // pair idempotent when the routine is not there yet.
        stmts.add("DROP " + (isFunction ? "FUNCTION" : "PROCEDURE") + " IF EXISTS "
                + qualify(schema, procedure.getName()));

        String body = procedure.getDefinition();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String header = buildHeader(procedure, schema, isFunction);
        String trimmed = body.trim();
        // information_schema.ROUTINES stores only the body, without the CREATE header.
        String full = trimmed.toUpperCase().startsWith("CREATE")
                ? trimmed
                : header + "\n" + trimmed;
        stmts.add(full);
        return stmts;
    }

    private String buildHeader(ProcedureMeta proc, String schema, boolean isFunction) {
        String params = proc.getParameters().stream()
                .filter(p -> !"RETURN".equalsIgnoreCase(p.getMode()))
                .map(p -> (isFunction ? "" : p.getMode() + " ")
                        + quoteIdentifier(p.getName()) + " " + p.getTypeName())
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("CREATE ")
                .append(isFunction ? "FUNCTION " : "PROCEDURE ")
                .append(qualify(schema, proc.getName()))
                .append('(').append(params).append(')');
        if (isFunction) {
            String ret = proc.getReturnType() == null ? "VARCHAR(255)" : proc.getReturnType();
            sb.append("\nRETURNS ").append(ret).append("\nDETERMINISTIC");
        }
        return sb.toString();
    }

    /**
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} — natively idempotent, and it keys off any
     * unique constraint, so it also absorbs unique-key conflicts, not just primary keys.
     */
    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        String insert = "INSERT INTO " + qualify(schema, table)
                + " (" + quotedList(columns) + ") VALUES (" + placeholders(columns.size()) + ")";
        if (pkColumns == null || pkColumns.isEmpty()) {
            return insert;
        }
        List<String> updatable = nonKeyColumns(columns, pkColumns);
        if (updatable.isEmpty()) {
            // Key-only table: a no-op update makes the statement a safe idempotent insert.
            String anyCol = quoteIdentifier(columns.get(0));
            return insert + " ON DUPLICATE KEY UPDATE " + anyCol + " = " + anyCol;
        }
        String updates = updatable.stream()
                .map(c -> quoteIdentifier(c) + " = VALUES(" + quoteIdentifier(c) + ")")
                .collect(Collectors.joining(", "));
        return insert + " ON DUPLICATE KEY UPDATE " + updates;
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        return baseSql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String getDisableConstraintsSql() {
        return "SET FOREIGN_KEY_CHECKS = 0";
    }

    @Override
    public String getEnableConstraintsSql() {
        return "SET FOREIGN_KEY_CHECKS = 1";
    }

}
