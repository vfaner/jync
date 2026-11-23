package com.qqmu.jync.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

/**
 * 神通数据库 (OSCAR / ShenTong) dialect.
 *
 * <p>Its catalog layout is PostgreSQL-derived, so it stays in the POSTGRES family for
 * identifiers, type mapping and metadata. Its DML, however, is Oracle-compatible: vendor
 * migration guidance recommends {@code MERGE INTO} for upsert and does not document
 * PostgreSQL's {@code INSERT … ON CONFLICT}. Emitting the PostgreSQL form would leave the
 * same gap that broke Vastbase targets (see {@link VastbaseDialect}), so this dialect
 * writes an Oracle-style {@code MERGE} instead. The unchanged-row guard is deliberately
 * not applied: products in this family vary in whether they accept a WHERE on the MERGE
 * update branch, and skipping it only costs extra replay accounting.
 */
@Component
public class OscarDialect extends PostgresDialect {

    @Override
    public DatabaseType explicitType() {
        return DatabaseType.OSCAR;
    }

    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        return getUpsertSql(schema, table, columns, pkColumns, null, false);
    }

    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns, Map<String, Integer> columnJdbcTypes) {
        return getUpsertSql(schema, table, columns, pkColumns, columnJdbcTypes, false);
    }

    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns, Map<String, Integer> columnJdbcTypes,
                               boolean guardUnchangedRows) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            return "INSERT INTO " + qualify(schema, table) + " (" + quotedList(columns)
                    + ") VALUES (" + placeholders(columns.size()) + ")";
        }
        String sourceRow = columns.stream()
                .map(c -> "? AS " + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        String onClause = pkColumns.stream()
                .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                .collect(Collectors.joining(" AND "));
        List<String> updatable = nonKeyColumns(columns, pkColumns);

        StringBuilder sql = new StringBuilder()
                .append("MERGE INTO ").append(qualify(schema, table))
                .append(" t USING (SELECT ").append(sourceRow).append(" FROM DUAL) s ON (")
                .append(onClause).append(')');
        if (!updatable.isEmpty()) {
            String updates = updatable.stream()
                    .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sql.append(" WHEN MATCHED THEN UPDATE SET ").append(updates);
        }
        String insertValues = columns.stream()
                .map(c -> "s." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        sql.append(" WHEN NOT MATCHED THEN INSERT (").append(quotedList(columns))
                .append(") VALUES (").append(insertValues).append(')');
        return sql.toString();
    }

    @Override
    public List<String> upsertBindOrder(List<String> columns, List<String> pkColumns) {
        // The MERGE names each value once, in the USING subquery.
        return new ArrayList<>(columns);
    }

    @Override
    public boolean supportsUpsertGuard() {
        return false;
    }

    /**
     * The MERGE projection binds each value once as {@code ? AS col}, without the
     * PostgreSQL {@code ?::timestamp}/{@code ?::time} cast. A MySQL source hands DATETIME
     * and TIME over as wall-clock text, and Oscar's Oracle-compatible conversion would run
     * it through the session's NLS formats — the same failure Oracle targets get. Parse the
     * literal into a real temporal before binding instead (as {@link OracleDialect} does).
     */
    @Override
    public boolean requiresTypedTemporalBind() {
        return true;
    }
}
