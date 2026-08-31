package com.synctool.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

/** SQL Server dialect. */
@Component
public class SqlServerDialect extends AbstractSqlDialect {

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.SQLSERVER;
    }

    @Override
    protected char quoteChar() {
        // Bracket quoting; handled specially since the delimiters differ.
        return '[';
    }

    @Override
    public String quoteIdentifier(String name) {
        if (name == null) {
            return null;
        }
        return "[" + name.replace("[", "").replace("]", "") + "]";
    }

    @Override
    protected String booleanType() {
        return "BIT";
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
        return "REAL";
    }

    @Override
    protected String doubleType() {
        return "FLOAT";
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
        // DATETIME2 has wider range and better precision than DATETIME.
        return "DATETIME2";
    }

    @Override
    protected String timestampTzType() {
        return "DATETIMEOFFSET";
    }

    @Override
    protected String blobType() {
        return "VARBINARY(MAX)";
    }

    @Override
    protected String clobType() {
        return "NVARCHAR(MAX)";
    }

    @Override
    protected String charType(int size) {
        return "NCHAR(" + Math.min(size, 4000) + ")";
    }

    @Override
    protected String varcharType(int size) {
        if (size <= 0 || size > 4000) {
            return "NVARCHAR(MAX)";
        }
        return "NVARCHAR(" + size + ")";
    }

    @Override
    protected String binaryType(int size) {
        if (size <= 0 || size > 8000) {
            return "VARBINARY(MAX)";
        }
        return "VARBINARY(" + size + ")";
    }

    @Override
    protected int maxIdentifierLength() {
        return 128;
    }

    @Override
    protected boolean indexNamesAreGlobal() {
        return false;
    }

    @Override
    protected String autoIncrementClause() {
        return "IDENTITY(1,1)";
    }

    @Override
    protected String currentTimestampFunction() {
        return "GETDATE()";
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        return "ALTER TABLE " + qualify(schema, table) + " ALTER COLUMN "
                + quoteIdentifier(column.getName()) + " " + mapType(column, sourceType)
                + (column.isNullable() ? " NULL" : " NOT NULL");
    }

    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType) {
        // T-SQL uses ADD without the COLUMN keyword.
        return "ALTER TABLE " + qualify(schema, table) + " ADD "
                + columnDefinition(column, sourceType);
    }

    @Override
    public List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType) {
        List<String> stmts = new ArrayList<>();
        String def = view.getDefinition();
        if (def != null && def.trim().toUpperCase().startsWith("CREATE")) {
            // sys.sql_modules returns the whole CREATE VIEW; rewrite it to ALTER-safe form.
            stmts.add("DROP VIEW IF EXISTS " + qualify(schema, view.getName()));
            stmts.add(def.trim());
        } else {
            stmts.add("DROP VIEW IF EXISTS " + qualify(schema, view.getName()));
            stmts.add("CREATE VIEW " + qualify(schema, view.getName()) + " AS " + def);
        }
        return stmts;
    }

    @Override
    public List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema,
                                              DatabaseType sourceType) {
        String body = procedure.getDefinition();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        boolean isFunction = procedure.isFunction();
        List<String> stmts = new ArrayList<>();
        stmts.add("DROP " + (isFunction ? "FUNCTION" : "PROCEDURE") + " IF EXISTS "
                + qualify(schema, procedure.getName()));
        String trimmed = body.trim();
        if (trimmed.toUpperCase().startsWith("CREATE")) {
            stmts.add(trimmed);
        } else {
            String params = procedure.getParameters().stream()
                    .filter(p -> !"RETURN".equalsIgnoreCase(p.getMode()))
                    .map(p -> "@" + stripAt(p.getName()) + " " + p.getTypeName()
                            + ("OUT".equalsIgnoreCase(p.getMode()) ? " OUTPUT" : ""))
                    .collect(Collectors.joining(", "));
            stmts.add("CREATE " + (isFunction ? "FUNCTION " : "PROCEDURE ")
                    + qualify(schema, procedure.getName()) + " (" + params + ")\nAS\n" + trimmed);
        }
        return stmts;
    }

    private String stripAt(String name) {
        return name == null ? "p" : (name.startsWith("@") ? name.substring(1) : name);
    }

    /**
     * {@code MERGE} is the only single-statement upsert in T-SQL. The trailing semicolon is
     * mandatory. Values are bound once, in the VALUES row constructor.
     */
    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            return "INSERT INTO " + qualify(schema, table) + " (" + quotedList(columns)
                    + ") VALUES (" + placeholders(columns.size()) + ")";
        }
        String onClause = pkColumns.stream()
                .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                .collect(Collectors.joining(" AND "));
        List<String> updatable = nonKeyColumns(columns, pkColumns);

        StringBuilder sb = new StringBuilder();
        sb.append("MERGE ").append(qualify(schema, table)).append(" WITH (HOLDLOCK) AS t ")
                .append("USING (VALUES (").append(placeholders(columns.size())).append(")) AS s (")
                .append(quotedList(columns)).append(") ON ").append(onClause);
        if (!updatable.isEmpty()) {
            String updates = updatable.stream()
                    .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updates);
        }
        String insertVals = columns.stream()
                .map(c -> "s." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        sb.append(" WHEN NOT MATCHED THEN INSERT (").append(quotedList(columns))
                .append(") VALUES (").append(insertVals).append(");");
        return sb.toString();
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        // OFFSET/FETCH requires an ORDER BY; the caller supplies one.
        return baseSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String getDisableConstraintsSql() {
        return "EXEC sp_MSforeachtable \"ALTER TABLE ? NOCHECK CONSTRAINT ALL\"";
    }

    @Override
    public String getEnableConstraintsSql() {
        return "EXEC sp_MSforeachtable \"ALTER TABLE ? WITH CHECK CHECK CONSTRAINT ALL\"";
    }

    @Override
    public String getDropIndexSql(com.synctool.dto.meta.IndexMeta index, String schema, String table) {
        return "DROP INDEX " + quoteIdentifier(indexNameFor(index, table))
                + " ON " + qualify(schema, table);
    }
}
