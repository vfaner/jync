package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * TDengine 3.x metadata through {@code information_schema} and {@code DESCRIBE}.
 *
 * <p>The driver's {@link java.sql.DatabaseMetaData} is not usable here: it reports every
 * text column as the storage class (VARCHAR and BINARY are indistinguishable, tags are
 * lost), so the facts come from the server's own description. {@code schema} is the
 * database name.
 *
 * <p>Super tables are listed like tables; their child tables are not. TDengine answers
 * {@code SELECT} on a super table but refuses an {@code INSERT} without a {@code tbname},
 * which the ETL checks handle separately.
 */
@Component
public class TdengineMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.TDENGINE;
    }

    @Override
    public List<String> listTableNames(Connection conn, String schema) throws SQLException {
        String database = database(conn, schema);
        List<String> names = new ArrayList<>();
        try (Statement statement = conn.createStatement()) {
            collect(statement, "SELECT table_name FROM information_schema.ins_tables"
                    + " WHERE db_name = " + literal(database)
                    + " AND type = 'NORMAL_TABLE'", names);
            collect(statement, "SELECT stable_name FROM information_schema.ins_stables"
                    + " WHERE db_name = " + literal(database), names);
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public List<String> listViewNames(Connection conn, String schema) {
        return List.of();
    }

    @Override
    public List<String> listProcedureNames(Connection conn, String schema) {
        return List.of();
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) {
        throw new UnsupportedOperationException("TDengine has no views");
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) {
        throw new UnsupportedOperationException("TDengine has no procedures");
    }

    @Override
    protected TableMeta readTable(Connection conn, String schema, String tableName,
                                  boolean includeDetail, boolean includeForeignKeys)
            throws SQLException {
        String database = database(conn, schema);
        boolean stable = isStable(conn, database, tableName);
        List<ColumnMeta> columns = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "DESCRIBE " + qualified(database, tableName))) {
            int ordinal = 1;
            while (rs.next()) {
                String field = rs.getString(1);
                if (field == null) {
                    continue;
                }
                String note = rs.getString(4);
                ColumnMeta column = column(field, rs.getString(2), rs.getInt(3), note, ordinal++);
                column.setPrimaryKey(isPrimaryKeyNote(note));
                columns.add(column);
                if (isTag(note)) {
                    tags.add(field);
                }
            }
        }
        TableMeta table = new TableMeta();
        table.setName(tableName);
        table.setSchema(schema);
        table.setColumns(columns);
        table.setPrimaryKeys(primaryKeys(columns, stable, tags));
        return table;
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            if (rs.next()) {
                String database = rs.getString(1);
                if (database != null && !database.isBlank()) {
                    return database;
                }
            }
        } catch (SQLException e) {
            // Keep the fallbacks below: a stale server answer is not worth failing a listing.
        }
        String catalog = conn.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        return conn.getMetaData().getUserName();
    }

    /**
     * TDengine has no row statistics, so the estimate is a {@code COUNT(*)} over the table —
     * on a stable, over every child table. The generic reader's TTL cache is what keeps a
     * table without a configured cursor (the strategy resolver asks for its size each cycle)
     * from being fully scanned every poll. Only the schema differs: it is the database name,
     * resolved from the session when the configured schema is blank.
     */
    @Override
    protected String rowCountSql(Connection conn, String schema, String tableName)
            throws SQLException {
        return "SELECT COUNT(*) FROM " + qualified(database(conn, schema), tableName);
    }

    private String database(Connection conn, String schema) throws SQLException {
        return schema == null || schema.isBlank() ? resolveDefaultSchema(conn) : schema;
    }

    private static boolean isStable(Connection conn, String database, String tableName)
            throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT stable_name FROM information_schema.ins_stables"
                             + " WHERE db_name = " + literal(database)
                             + " AND stable_name = " + literal(tableName))) {
            return rs.next();
        }
    }

    private static void collect(Statement statement, String sql, List<String> names)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null) {
                    names.add(name);
                }
            }
        }
    }

    /**
     * The timestamp column is always unique, a declared primary key adds to it, and a super
     * table adds every tag: the same {@code (ts, key)} can exist in two child tables.
     */
    private static List<String> primaryKeys(List<ColumnMeta> columns, boolean stable,
                                            List<String> tags) {
        List<String> keys = new ArrayList<>();
        if (!columns.isEmpty() && columns.get(0).getJdbcType() == Types.TIMESTAMP) {
            keys.add(columns.get(0).getName());
        }
        for (ColumnMeta column : columns) {
            if (column.isPrimaryKey() && !keys.contains(column.getName())) {
                keys.add(column.getName());
            }
        }
        if (stable) {
            for (String tag : tags) {
                if (!keys.contains(tag)) {
                    keys.add(tag);
                }
            }
        }
        for (ColumnMeta column : columns) {
            column.setPrimaryKey(keys.contains(column.getName()));
        }
        return keys;
    }

    private static ColumnMeta column(String name, String typeText, int length, String note,
                                     int ordinal) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setOrdinalPosition(ordinal);
        column.setNullable(true);

        String original = typeText == null ? "" : typeText.trim();
        String upper = original.toUpperCase(Locale.ROOT);
        boolean unsigned = upper.endsWith(" UNSIGNED");
        String base = unsigned
                ? upper.substring(0, upper.length() - " UNSIGNED".length()).trim() : upper;
        // Keep the server's spelling ("TINYINT UNSIGNED", "DECIMAL(20, 4)") for the UI;
        // the DECIMAL branch below narrows it to the canonical name.
        column.setTypeName(original.isEmpty() ? base : original);

        if (unsigned && "BIGINT".equals(base)) {
            // getObject answers a BigInteger here, which pgjdbc refuses to bind; DECIMAL(20, 0)
            // holds the whole unsigned 64-bit range and binds through BigDecimal.
            column.setJdbcType(Types.DECIMAL);
            column.setSize(20);
            column.setDecimalDigits(0);
            return column;
        }
        if (base.startsWith("DECIMAL")) {
            column.setTypeName("DECIMAL");
            int open = base.indexOf('(');
            int close = base.indexOf(')');
            if (open > 0 && close > open) {
                String[] parts = base.substring(open + 1, close).split(",");
                column.setSize(Integer.parseInt(parts[0].trim()));
                if (parts.length > 1) {
                    column.setDecimalDigits(Integer.parseInt(parts[1].trim()));
                }
            }
            column.setJdbcType(Types.DECIMAL);
            return column;
        }
        switch (base) {
            case "TIMESTAMP" -> column.setJdbcType(Types.TIMESTAMP);
            case "BOOL" -> column.setJdbcType(Types.BOOLEAN);
            case "TINYINT" -> column.setJdbcType(unsigned ? Types.SMALLINT : Types.TINYINT);
            case "SMALLINT" -> column.setJdbcType(unsigned ? Types.INTEGER : Types.SMALLINT);
            case "INT" -> column.setJdbcType(unsigned ? Types.BIGINT : Types.INTEGER);
            case "BIGINT" -> column.setJdbcType(Types.BIGINT);
            case "FLOAT" -> column.setJdbcType(Types.REAL);
            case "DOUBLE" -> column.setJdbcType(Types.DOUBLE);
            case "NCHAR" -> {
                column.setJdbcType(Types.NVARCHAR);
                column.setSize(length > 0 ? length : null);
            }
            case "VARBINARY", "GEOMETRY" -> {
                column.setJdbcType(Types.VARBINARY);
                column.setSize(length > 0 ? length : null);
            }
            case "JSON" -> column.setJdbcType(Types.VARCHAR);
            default -> {
                // VARCHAR, and BINARY (DESCRIBE reports the storage class as VARCHAR) —
                // both read back as bytes, so the text read applies to both.
                column.setJdbcType(Types.VARCHAR);
                column.setSize(length > 0 ? length : null);
            }
        }
        return column;
    }

    private static boolean isTag(String note) {
        return note != null && note.toUpperCase(Locale.ROOT).contains("TAG");
    }

    private static boolean isPrimaryKeyNote(String note) {
        return note != null && note.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
    }

    private static String qualified(String database, String table) {
        return "`" + database.replace("`", "``") + "`.`" + table.replace("`", "``") + "`";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
