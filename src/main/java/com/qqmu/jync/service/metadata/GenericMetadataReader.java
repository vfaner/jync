package com.qqmu.jync.service.metadata;

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
import java.util.concurrent.ConcurrentHashMap;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

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

    /**
     * Short-lived cache for the {@code COUNT(*)} fallback of {@link #estimateRowCount}.
     *
     * <p>Product readers that can ask the optimizer's statistics do so on every poll — those
     * catalog queries are cheap. The fallback here is a full {@code COUNT(*)}, the most
     * expensive statement the sync can issue against a source, and it was running on every
     * poll cycle (seconds apart) for any table whose statistics were unavailable. The
     * estimate only steers strategy choice (the full-compare ceiling, deletion-sync
     * eligibility), so a value a few minutes stale changes nothing that matters.
     */
    private static final long COUNT_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Hard cap against unbounded growth; expired entries are purged before this is hit. */
    private static final int COUNT_CACHE_MAX = 1000;

    private final Map<String, TimedCount> countCache = new ConcurrentHashMap<>();

    private record TimedCount(long at, long count) {
    }

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
                if (name != null && !isSystemObject(rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"), name)) {
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
                if (name != null && !isSystemObject(rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"), name)) {
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
                if (name != null && !isSystemObject(rs.getString("PROCEDURE_CAT"),
                        rs.getString("PROCEDURE_SCHEM"), name)
                        && isStandaloneRoutine(name)) {
                    names.add(name);
                }
            }
        } catch (SQLException e) {
            log.debug("getProcedures() unsupported or failed: {}", e.getMessage());
        }
        try (ResultSet rs = md.getFunctions(catalogFor(conn, schema), schemaPattern(schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                if (name != null && !isSystemObject(rs.getString("FUNCTION_CAT"),
                        rs.getString("FUNCTION_SCHEM"), name)
                        && isStandaloneRoutine(name)) {
                    names.add(name);
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
            while (rs.next()) {
                if (isRequestedObject(rs.getString("TABLE_NAME"), tableName,
                        rs.getString("TABLE_SCHEM"), schema)) {
                    table.setRemarks(rs.getString("REMARKS"));
                    break;
                }
            }
        }

        // Primary key first, so columns can be flagged as they are read.
        // Sorted by KEY_SEQ: composite key order is significant for upsert predicates.
        Map<Short, String> pkBySeq = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schemaPattern(schema), tableName)) {
            while (rs.next()) {
                // tableName is a SQL LIKE pattern to the driver ('_' matches any char), so a
                // name like A_B also matches AXB. Keep only rows for the table we asked for.
                if (!isRequestedObject(rs.getString("TABLE_NAME"), tableName,
                        rs.getString("TABLE_SCHEM"), schema)) {
                    continue;
                }
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
                if (!isRequestedObject(rs.getString("TABLE_NAME"), tableName,
                        rs.getString("TABLE_SCHEM"), schema)) {
                    continue;
                }
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
                // Same LIKE-pattern guard as getPrimaryKeys: the third argument may match more
                // than the one table requested, so discard rows belonging to another table.
                if (!isRequestedObject(rs.getString("TABLE_NAME"), tableName,
                        rs.getString("TABLE_SCHEM"), schema)) {
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
                // LIKE-pattern guard again: the rows describing this table are its imported
                // (foreign) keys, so match on the FKTABLE_* columns, not the referenced table.
                if (!isRequestedObject(rs.getString("FKTABLE_NAME"), tableName,
                        rs.getString("FKTABLE_SCHEM"), schema)) {
                    continue;
                }
                TableMeta.ForeignKeyMeta fk = new TableMeta.ForeignKeyMeta();
                fk.setName(rs.getString("FK_NAME"));
                fk.setColumnName(rs.getString("FKCOLUMN_NAME"));
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
            MetadataTimeouts.apply(ps);
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
            MetadataTimeouts.apply(ps);
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
                // The name argument is a pattern; overloaded package routines may also be
                // reported with their package prefix, which listProcedureNames stripped.
                if (!procedureName.equals(stripPackagePrefix(rs.getString("PROCEDURE_NAME")))) {
                    continue;
                }
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

        List<String> tableNames = tables != null && !tables.isEmpty()
                ? tables : listTableNames(conn, schema);
        for (String name : tableNames) {
            try {
                meta.getTables().add(readTable(conn, schema, name));
            } catch (SQLException e) {
                // One unreadable table (e.g. permissions) must not abort the whole pass.
                // Record it as unreadable (not absent): it demonstrably exists, so the change
                // detector must not read its absence here as a source-side DROP.
                log.warn("Skipping table {} — could not read metadata: {}", name, e.getMessage());
                meta.addUnreadableTable(name);
            }
        }

        if (includeViews) {
            List<String> viewNames = views != null && !views.isEmpty()
                    ? views : listViewNames(conn, schema);
            for (String name : viewNames) {
                try {
                    meta.getViews().add(readView(conn, schema, name));
                } catch (SQLException e) {
                    // Marked unreadable, not absent: a view the dictionary fails to return this
                    // pass must not be dropped at the target under allowDrop.
                    log.warn("Skipping view {} — could not read definition: {}", name, e.getMessage());
                    meta.addUnreadableView(name);
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
                    // Same unreadable-not-absent contract as tables/views above.
                    log.warn("Skipping routine {} — could not read definition: {}", name, e.getMessage());
                    meta.addUnreadableProcedure(name);
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
        // Keyed by JDBC URL as well: one reader instance serves every project of its product,
        // and the same schema.table name means different tables on different servers. A
        // driver that reports no URL (the spec allows null, and some custom drivers do)
        // gets no caching at all: without the URL component two servers' tables would share
        // one key, and a wrong count is worse than a repeated COUNT(*).
        String url = conn.getMetaData().getURL();
        String cacheKey = url == null ? null : url + "|" + schema + "|" + tableName;
        long now = System.currentTimeMillis();
        if (cacheKey != null) {
            TimedCount cached = countCache.get(cacheKey);
            if (cached != null && now - cached.at() < COUNT_CACHE_TTL_MS) {
                return cached.count();
            }
        }

        String qualified = schema == null || schema.isBlank()
                ? quote(conn, tableName)
                : quote(conn, schema) + "." + quote(conn, tableName);
        long count;
        try (Statement st = conn.createStatement()) {
            MetadataTimeouts.apply(st);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                count = rs.next() ? rs.getLong(1) : 0L;
            }
        }

        if (cacheKey == null) {
            return count;
        }
        if (countCache.size() >= COUNT_CACHE_MAX) {
            // 上限只防无界增长：先清过期项，仍超就全清——重建代价不过是下一轮再 COUNT 一次。
            countCache.entrySet().removeIf(e -> now - e.getValue().at() >= COUNT_CACHE_TTL_MS);
            if (countCache.size() >= COUNT_CACHE_MAX) {
                countCache.clear();
            }
        }
        countCache.put(cacheKey, new TimedCount(now, count));
        return count;
    }

    protected String quote(Connection conn, String identifier) throws SQLException {
        String q = conn.getMetaData().getIdentifierQuoteString();
        if (q == null || q.isBlank() || " ".equals(q)) {
            return identifier;
        }
        // Double any quote chars inside the identifier, or an embedded one closes the quoted
        // name early — a table named a"b would otherwise break the generated identifier.
        return q + identifier.replace(q, q + q) + q;
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

    /**
     * True when a result-set row is really the object that was asked for.
     *
     * <p>The name arguments of {@code getTables}/{@code getColumns} are <em>patterns</em>:
     * {@code _} matches any single character, so reading {@code user_info} also returns rows
     * for {@code user-info} or {@code userXinfo}, and merging those columns would silently
     * corrupt the table's shape. The schema is checked the same way (a schema name is also a
     * pattern), but tolerated when the driver reports none — MySQL, for instance, carries the
     * database in the catalog and leaves TABLE_SCHEM null.
     */
    protected boolean isRequestedObject(String reportedName, String requestedName,
                                        String reportedSchema, String requestedSchema) {
        if (!requestedName.equals(reportedName)) {
            return false;
        }
        boolean schemaRequested = requestedSchema != null && !requestedSchema.isBlank();
        // The driver is queried with schemaPattern(requestedSchema), which subclasses may
        // normalize (Oracle/DB2 uppercase it). The reported schema then matches the
        // PATTERN, not the raw request — compare against both or a lowercase request
        // would filter out every row on a case-normalizing driver.
        return !schemaRequested || reportedSchema == null
                || reportedSchema.equals(requestedSchema)
                || reportedSchema.equals(schemaPattern(requestedSchema));
    }

    /**
     * Catalogs the MySQL-family server itself owns ({@code mysql}, {@code sys},
     * {@code information_schema}, {@code performance_schema}). A business database never
     * has these names, and {@code getTables} with a null catalog can otherwise return rows
     * from all of them.
     */
    private static final Set<String> SYSTEM_CATALOGS = Set.of(
            "MYSQL", "SYS", "INFORMATION_SCHEMA", "PERFORMANCE_SCHEMA");

    /**
     * Schemas/owners that ship database-engine objects rather than user data.
     *
     * <p>Membership is deliberately by <em>schema/owner</em>, never by table-name prefix:
     * business schemas are full of tables like RuoYi's {@code sys_user}, {@code sys_role},
     * {@code pg_...}, and filtering those by name both skipped them on sync and, worse,
     * dropped them at the target under allowDrop.
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            // Cross-product standard catalog views.
            "INFORMATION_SCHEMA",
            // SQL Server built-in schemas.
            "SYS", "GUEST",
            // PostgreSQL-family: the pg_catalog namespace (pg_temp/pg_toast are prefixes).
            "PG_CATALOG",
            // Oracle-family built-in administrative users.
            "SYSTEM", "OUTLN", "DBSNMP", "APPQOSSYS", "AUDSYS",
            "GSMADMIN_INTERNAL", "GSMUSER", "GSMROOTUSER",
            "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "REMOTE_SCHEDULER_AGENT",
            "MDSYS", "XDB", "CTXSYS", "WMSYS", "EXFSYS", "OLAPSYS",
            "ORDSYS", "ORDPLUGINS", "ORDDATA", "SI_INFORMTN_SCHEMA",
            "DVSYS", "LBACSYS", "ANONYMOUS", "SYSMAN", "MGMT_VIEW",
            "OWBSYS", "OWBSYS_AUDIT", "ORACLE_OCM", "SYS$UMF",
            "FLOWS_FILES", "APEX_PUBLIC_USER", "APEX_INSTANCE_ADMIN_USER",
            // DB2 built-in schemas.
            "SYSIBM", "SYSCAT", "SYSSTAT", "SYSFUN", "SYSPROC");

    /** Oracle APEX versioned schemas ({@code APEX_030200}, {@code APEX_040200}…) and
     *  old HTMLDB {@code FLOWS_} schemas. */
    private static final List<String> SYSTEM_SCHEMA_PREFIXES = List.of(
            "APEX_", "FLOWS_",
            // PostgreSQL session temp schemas (pg_temp_<oid>) and TOAST namespaces; pg_toast
            // is covered by the same prefix. The un-suffixed pg_catalog is in the set.
            "PG_TEMP", "PG_TOAST");

    /**
     * Filters out recycle-bin and engine-owned objects that must never be synced.
     *
     * <p>Engine ownership is judged from the catalog/schema the row was reported in; the
     * only name-prefix filters left are objects the database itself generates inside a
     * user schema: Oracle's recycle bin ({@code BIN$}), materialized-view logs
     * ({@code MLOG$}) and SQLite's internal tables ({@code sqlite_}).
     */
    protected boolean isSystemObject(String catalog, String schema, String name) {
        if (name == null) {
            return true;
        }
        String upperName = name.toUpperCase();
        if (upperName.startsWith("BIN$")           // Oracle recycle bin
                || upperName.startsWith("MLOG$")   // Oracle materialized view logs
                || upperName.startsWith("SQLITE_")) { // SQLite internal tables
            return true;
        }
        if (catalog != null && SYSTEM_CATALOGS.contains(catalog.toUpperCase())) {
            return true;
        }
        if (schema != null) {
            String upperSchema = schema.toUpperCase();
            if (SYSTEM_SCHEMAS.contains(upperSchema)) {
                return true;
            }
            for (String prefix : SYSTEM_SCHEMA_PREFIXES) {
                if (upperSchema.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Oracle reports package routines as {@code PKG.PROC}. */
    protected String stripPackagePrefix(String name) {
        // PROCEDURE_NAME is nullable per the JDBC spec; some drivers report a null row.
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /**
     * Whether a JDBC-reported routine name denotes a standalone routine.
     *
     * <p>Oracle (and DM/Yashan) report members of a package as {@code PKG.PROC}. They are not
     * independently creatable on targets without packages (MySQL, SQL Server), and listing them
     * under the stripped name collides with same-named members of other packages and with the
     * standalone routine — the body lookup then returns nothing and the object silently skips.
     * Exclude them from selection instead; package member tests (line 351) still use
     * {@link #stripPackagePrefix} for configs saved before this change.
     */
    protected boolean isStandaloneRoutine(String name) {
        return name.indexOf('.') < 0;
    }
}
