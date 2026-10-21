package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Reads normalized metadata from one database product.
 *
 * <p>Implementations may rely on {@link java.sql.DatabaseMetaData} where it is adequate
 * and fall back to product system tables where it is not — notably view and routine
 * bodies, which JDBC does not expose.
 */
public interface MetadataReader {

    /** Products this reader handles. */
    boolean supports(DatabaseType type);

    /** Table names visible in the effective schema, excluding views. */
    List<String> listTableNames(Connection conn, String schema) throws SQLException;

    List<String> listViewNames(Connection conn, String schema) throws SQLException;

    List<String> listProcedureNames(Connection conn, String schema) throws SQLException;

    /** Full definition of one table: columns, primary key, indexes and foreign keys. */
    TableMeta readTable(Connection conn, String schema, String tableName) throws SQLException;

    ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException;

    ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException;

    /**
     * Reads everything the given selection covers in one pass.
     *
     * @param tables     table names to read, null for all discovered, empty for none
     * @param views      view names to read, null for all, empty for none; ignored when
     *                   {@code includeViews} is false
     * @param procedures routine names to read, null for all, empty for none; ignored when
     *                   {@code includeProcedures} is false
     */
    DatabaseMeta readAll(Connection conn, String schema,
                         List<String> tables, List<String> views, List<String> procedures,
                         boolean includeViews, boolean includeProcedures) throws SQLException;

    /**
     * The same selection as {@link #readAll}, without the detail only structure sync consumes:
     * the index and foreign-key dictionaries and the product's comment dictionaries. Columns,
     * the primary key, autoincrement enrichment and the row-level SCN probe are always read —
     * the data path needs all four.
     *
     * <p>Used by cycles that have structure sync switched off, where change detection does not
     * run and those dictionaries would be fetched and then thrown away: two to four extra
     * dictionary round trips per table on every poll.
     *
     * <p>The default implementation delegates to the full read, so a reader that cannot split
     * the two stays correct — only slower.
     */
    default DatabaseMeta readAllForData(Connection conn, String schema,
                                        List<String> tables, List<String> views,
                                        List<String> procedures, boolean includeViews,
                                        boolean includeProcedures) throws SQLException {
        return readAll(conn, schema, tables, views, procedures, includeViews, includeProcedures);
    }

    /**
     * The schema to use when the user left it blank — usually the connection user or the
     * current catalog, depending on the product.
     */
    String resolveDefaultSchema(Connection conn) throws SQLException;

    /** Approximate row count, from statistics when available and {@code COUNT(*)} otherwise. */
    long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException;
}
