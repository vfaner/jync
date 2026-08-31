package com.synctool.service.metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.DatabaseMeta;
import com.synctool.dto.meta.IndexMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Metadata reader built on {@link DatabaseMetaData} alone.
 *
 * <p>This is the fallback for products without a dedicated reader — including
 * {@link DatabaseType#CUSTOM} — and the base class the product-specific readers extend to
 * override only the parts JDBC cannot supply, namely view and routine bodies.
 */
@Slf4j
public class GenericMetadataReader implements MetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        // Registered last, as the catch-all.
        return true;
    }

    @Override
    public List<String> listTableNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getTables(catalogFor(conn, schema), schemaPattern(schema), "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !isSystemObject(name)) {
                    names.add(name);
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public List<String> listViewNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getTables(catalogFor(conn, schema), schemaPattern(schema), "%",
                new String[]{"VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !isSystemObject(name)) {
                    names.add(name);
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    @Override
    public List<String> listProcedureNames(Connection conn, String schema) throws SQLException {
        // Deduplicated: overloaded routines appear once per signature in JDBC.
        Set<String> names = new LinkedHashSet<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getProcedures(catalogFor(conn, schema), schemaPattern(schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("PROCEDURE_NAME");
                if (name != null && !isSystemObject(name)) {
                    names.add(stripPackagePrefix(name));
                }
            }
        } catch (SQLException e) {
            log.debug("getProcedures() unsupported or failed: {}", e.getMessage());
        }
        try (ResultSet rs = md.getFunctions(catalogFor(conn, schema), schemaPattern(schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                if (name != null && !isSystemObject(name)) {
                    names.add(stripPackagePrefix(name));
                }
            }
        } catch (SQLException e) {
            log.debug("getFunctions() unsupported or failed: {}", e.getMessage());
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }

    @Override
    public TableMeta readTable(Connection conn, String schema, String tableName) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        TableMeta table = new TableMeta();
        table.setName(tableName);
        table.setSchema(schema);

        String catalog = catalogFor(conn, schema);

        // Table comment.
        try (ResultSet rs = md.getTables(catalog, schemaPattern(schema), tableName, new String[]{"TABLE"})) {
            if (rs.next()) {
                table.setRemarks(rs.getString("REMARKS"));
            }
        }

        // Primary key first, so columns can be flagged as they are read.
        // Sorted by KEY_SEQ: composite key order is significant for upsert predicates.
        Map<Short, String> pkBySeq = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schemaPattern(schema), tableName)) {
            while (rs.next()) {
                pkBySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException e) {
            log.debug("getPrimaryKeys failed for {}: {}", tableName, e.getMessage());
        }
        table.setPrimaryKeys(new ArrayList<>(pkBySeq.values()));

        Set<String> pkUpper = new LinkedHashSet<>();
        table.getPrimaryKeys().forEach(pk -> pkUpper.add(pk.toUpperCase()));

        // Columns.
        try (ResultSet rs = md.getColumns(catalog, schemaPattern(schema), tableName, "%")) {
            while (rs.next()) {
                ColumnMeta col = new ColumnMeta();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setTypeName(rs.getString("TYPE_NAME"));
                col.setJdbcType(rs.getInt("DATA_TYPE"));
                int size = rs.getInt("COLUMN_SIZE");
                col.setSize(rs.wasNull() ? null : size);
                int digits = rs.getInt("DECIMAL_DIGITS");
                col.setDecimalDigits(rs.wasNull() ? null : digits);
                col.setNullable(rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                col.setDefaultValue(rs.getString("COLUMN_DEF"));
                col.setRemarks(rs.getString("REMARKS"));
                col.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                col.setAutoIncrement(readAutoIncrement(rs));
                col.setPrimaryKey(pkUpper.contains(col.getName().toUpperCase()));
                table.getColumns().add(col);
            }
        }
        table.getColumns().sort((a, b) -> Integer.compare(a.getOrdinalPosition(), b.getOrdinalPosition()));

        table.setIndexes(readIndexes(conn, schema, tableName, pkUpper));
        table.setForeignKeys(readForeignKeys(conn, schema, tableName));
        return table;
    }

    /** {@code IS_AUTOINCREMENT} is optional in JDBC; absence is not an error. */
    private boolean readAutoIncrement(ResultSet rs) {
        try {
            return "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
        } catch (SQLException e) {
            return false;
        }
    }

    protected List<IndexMeta> readIndexes(Connection conn, String schema, String tableName,
                                          Set<String> pkColumnsUpper) {
        Map<String, IndexMeta> byName = new LinkedHashMap<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(
                catalogFor(conn, schema), schemaPattern(schema), tableName, false, true)) {
            while (rs.next()) {
                // tableIndexStatistic rows carry cardinality, not an index.
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                IndexMeta index = byName.computeIfAbsent(indexName, n -> {
                    IndexMeta m = new IndexMeta();
                    m.setName(n);
                    m.setTableName(tableName);
                    return m;
                });
                try {
                    index.setUnique(!rs.getBoolean("NON_UNIQUE"));
                } catch (SQLException ignored) {
                    // Some drivers omit NON_UNIQUE; assume non-unique.
                }
                index.getColumns().add(columnName);
            }
        } catch (SQLException e) {
            log.debug("getIndexInfo failed for {}: {}", tableName, e.getMessage());
        }

        // Mark the index that implements the primary key so it is not created twice.
        for (IndexMeta index : byName.values()) {
            Set<String> cols = new LinkedHashSet<>();
            index.getColumns().forEach(c -> cols.add(c.toUpperCase()));
            if (!pkColumnsUpper.isEmpty() && cols.equals(pkColumnsUpper) && index.isUnique()) {
                index.setPrimaryKey(true);
            }
            if ("PRIMARY".equalsIgnoreCase(index.getName())) {
                index.setPrimaryKey(true);
            }
        }
        return new ArrayList<>(byName.values());
    }

    protected List<TableMeta.ForeignKeyMeta> readForeignKeys(Connection conn, String schema, String tableName) {
        List<TableMeta.ForeignKeyMeta> fks = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getImportedKeys(
                catalogFor(conn, schema), schemaPattern(schema), tableName)) {
            while (rs.next()) {
                TableMeta.ForeignKeyMeta fk = new TableMeta.ForeignKeyMeta();
                fk.setName(rs.getString("FK_NAME"));
                fk.setColumnName(rs.getString("FKCOLUMN_NAME"));
                fk.setReferencedTable(rs.getString("PKTABLE_NAME"));
                fk.setReferencedColumn(rs.getString("PKCOLUMN_NAME"));
                fks.add(fk);
            }
        } catch (SQLException e) {
            log.debug("getImportedKeys failed for {}: {}", tableName, e.getMessage());
        }
        return fks;
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        // JDBC has no portable way to read a view body, but INFORMATION_SCHEMA.VIEWS is part
        // of the SQL standard and is present on most products — including ones without a
        // dedicated reader here. Trying it means an unrecognized database often still gets
        // working view sync instead of silently having its views skipped.
        view.setDefinition(readViewDefinitionFromInformationSchema(conn, schema, viewName));
        return view;
    }

    /** Reads a view body from the standard catalog. Returns null when unavailable. */
    protected String readViewDefinitionFromInformationSchema(Connection conn, String schema,
                                                             String viewName) {
        String sql = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS "
                + "WHERE TABLE_NAME = ? AND (? IS NULL OR TABLE_SCHEMA = ?)";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            String schemaArg = schema == null || schema.isBlank() ? null : schema;
            ps.setString(1, viewName);
            ps.setString(2, schemaArg);
            ps.setString(3, schemaArg);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String definition = rs.getString(1);
                    if (definition != null && !definition.isBlank()) {
                        return definition;
                    }
                }
            }
        } catch (SQLException e) {
            // No INFORMATION_SCHEMA, or a different column layout. Not an error: the caller
            // treats a null definition as "cannot sync this view" and reports it.
            log.debug("INFORMATION_SCHEMA.VIEWS lookup failed for {}: {}", viewName, e.getMessage());
        }
        // Retry unqualified: some products reject the schema predicate above.
        String fallback = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_NAME = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(fallback)) {
            ps.setString(1, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.debug("Unqualified INFORMATION_SCHEMA.VIEWS lookup failed for {}: {}",
                    viewName, e.getMessage());
        }
        return null;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setName(procedureName);
        proc.setSchema(schema);
        try (ResultSet rs = conn.getMetaData().getProcedureColumns(
                catalogFor(conn, schema), schemaPattern(schema), procedureName, "%")) {
            while (rs.next()) {
                ProcedureMeta.ParamMeta p = new ProcedureMeta.ParamMeta();
                p.setName(rs.getString("COLUMN_NAME"));
                p.setTypeName(rs.getString("TYPE_NAME"));
                int size = rs.getInt("PRECISION");
                p.setSize(rs.wasNull() ? null : size);
                p.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                p.setMode(paramMode(rs.getShort("COLUMN_TYPE")));
                proc.getParameters().add(p);
            }
        } catch (SQLException e) {
            log.debug("getProcedureColumns failed for {}: {}", procedureName, e.getMessage());
        }
        return proc;
    }

    private String paramMode(short columnType) {
        switch (columnType) {
            case DatabaseMetaData.procedureColumnOut:
                return "OUT";
            case DatabaseMetaData.procedureColumnInOut:
                return "INOUT";
            case DatabaseMetaData.procedureColumnReturn:
                return "RETURN";
            default:
                return "IN";
        }
    }

    @Override
    public DatabaseMeta readAll(Connection conn, String schema,
                                List<String> tables, List<String> views, List<String> procedures,
                                boolean includeViews, boolean includeProcedures) throws SQLException {
        DatabaseMeta meta = new DatabaseMeta();
        meta.setSchema(schema);
        DatabaseMetaData md = conn.getMetaData();
        meta.setProductName(md.getDatabaseProductName());
        meta.setProductVersion(md.getDatabaseProductVersion());

        List<String> tableNames = tables != null && !tables.isEmpty()
                ? tables : listTableNames(conn, schema);
        for (String name : tableNames) {
            try {
                meta.getTables().add(readTable(conn, schema, name));
            } catch (SQLException e) {
                // One unreadable table (e.g. permissions) must not abort the whole pass.
                log.warn("Skipping table {} — could not read metadata: {}", name, e.getMessage());
            }
        }

        if (includeViews) {
            List<String> viewNames = views != null && !views.isEmpty()
                    ? views : listViewNames(conn, schema);
            for (String name : viewNames) {
                try {
                    meta.getViews().add(readView(conn, schema, name));
                } catch (SQLException e) {
                    log.warn("Skipping view {} — could not read definition: {}", name, e.getMessage());
                }
            }
        }

        if (includeProcedures) {
            List<String> procNames = procedures != null && !procedures.isEmpty()
                    ? procedures : listProcedureNames(conn, schema);
            for (String name : procNames) {
                try {
                    meta.getProcedures().add(readProcedure(conn, schema, name));
                } catch (SQLException e) {
                    log.warn("Skipping routine {} — could not read definition: {}", name, e.getMessage());
                }
            }
        }
        return meta;
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        String schema = conn.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        String catalog = conn.getCatalog();
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        return conn.getMetaData().getUserName();
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        String qualified = schema == null || schema.isBlank()
                ? quote(conn, tableName)
                : quote(conn, schema) + "." + quote(conn, tableName);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    protected String quote(Connection conn, String identifier) throws SQLException {
        String q = conn.getMetaData().getIdentifierQuoteString();
        if (q == null || q.isBlank() || " ".equals(q)) {
            return identifier;
        }
        return q + identifier + q;
    }

    /**
     * Catalog argument for {@link DatabaseMetaData} calls. MySQL treats the catalog as the
     * database and ignores schema, so subclasses override the pairing as needed.
     */
    protected String catalogFor(Connection conn, String schema) {
        try {
            return conn.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    protected String schemaPattern(String schema) {
        return schema == null || schema.isBlank() ? null : schema;
    }

    /** Filters out recycle-bin and system-generated objects that must never be synced. */
    protected boolean isSystemObject(String name) {
        if (name == null) {
            return true;
        }
        String upper = name.toUpperCase();
        return upper.startsWith("BIN$")          // Oracle recycle bin
                || upper.startsWith("MLOG$")     // Oracle materialized view logs
                || upper.startsWith("SYS_")
                || upper.startsWith("PG_")       // PostgreSQL internals
                || upper.startsWith("SQLITE_");
    }

    /** Oracle reports package routines as {@code PKG.PROC}; keep only the routine name. */
    protected String stripPackagePrefix(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
