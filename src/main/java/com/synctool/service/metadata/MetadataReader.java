package com.synctool.service.metadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.synctool.dto.meta.DatabaseMeta;
import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

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
     * @param tables     table names to read, or null for all
     * @param views      view names to read, or null for all; ignored when {@code includeViews} is false
     * @param procedures routine names to read, or null for all; ignored when {@code includeProcedures} is false
     */
    DatabaseMeta readAll(Connection conn, String schema,
                         List<String> tables, List<String> views, List<String> procedures,
                         boolean includeViews, boolean includeProcedures) throws SQLException;

    /**
     * The schema to use when the user left it blank — usually the connection user or the
     * current catalog, depending on the product.
     */
    String resolveDefaultSchema(Connection conn) throws SQLException;

    /** Approximate row count, from statistics when available and {@code COUNT(*)} otherwise. */
    long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException;
}
