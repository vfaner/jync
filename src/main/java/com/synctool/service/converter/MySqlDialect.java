package com.synctool.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.IndexMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

/** MySQL / MariaDB dialect. */
@Component
public class MySqlDialect extends AbstractSqlDialect {

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.MYSQL;
    }

    @Override
    protected char quoteChar() {
        return '`';
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
        return "DATETIME";
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

    @Override
    protected String createTableSuffix(TableMeta table) {
        StringBuilder sb = new StringBuilder(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        if (table.getRemarks() != null && !table.getRemarks().isBlank()) {
            sb.append(" COMMENT='").append(escape(table.getRemarks())).append('\'');
        }
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        return "ALTER TABLE " + qualify(schema, table) + " MODIFY COLUMN "
                + columnDefinition(column, sourceType);
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

    @Override
    protected String renderBooleanDefault(boolean value) {
        return value ? "1" : "0";
    }
}
