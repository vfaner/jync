package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL Server reader. Bodies come from {@code sys.sql_modules}, which returns the original
 * {@code CREATE} text including the header.
 */
@Component
@Slf4j
public class SqlServerMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.SQLSERVER;
    }

    @Override
    protected String schemaPattern(String schema) {
        return schema == null || schema.isBlank() ? "dbo" : schema;
    }

    /**
     * {@inheritDoc}
     *
     * <p>SQL Server hides metadata for objects the account has no permission on, but
     * {@code VIEW DEFINITION} at database scope (also implied by {@code db_owner}) reveals
     * every object. {@code HAS_PERMS_BY_NAME} answers for the current security context,
     * including role membership. Without it the answer stays conservative.
     */
    @Override
    public boolean schemaListingIsComplete(Connection conn, String schema) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DEFINITION')")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            log.debug("Could not probe SQL Server metadata visibility; drops stay conservative: {}",
                    e.getMessage());
            return false;
        }
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT SCHEMA_NAME()")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("SCHEMA_NAME() failed: {}", e.getMessage());
        }
        return "dbo";
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        String sql = "SELECT m.definition FROM sys.sql_modules m "
                + "JOIN sys.views v ON v.object_id = m.object_id "
                + "JOIN sys.schemas s ON s.schema_id = v.schema_id "
                + "WHERE s.name = ? AND v.name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    view.setDefinition(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read SQL Server view definition for {}: {}", viewName, e.getMessage());
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);
        String sql = "SELECT m.definition, o.type_desc FROM sys.sql_modules m "
                + "JOIN sys.objects o ON o.object_id = m.object_id "
                + "JOIN sys.schemas s ON s.schema_id = o.schema_id "
                + "WHERE s.name = ? AND o.name = ? "
                + "AND o.type IN ('P','FN','IF','TF')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, procedureName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setDefinition(rs.getString("definition"));
                    String typeDesc = rs.getString("type_desc");
                    proc.setRoutineType(typeDesc != null && typeDesc.contains("FUNCTION")
                            ? "FUNCTION" : "PROCEDURE");
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read SQL Server routine definition for {}: {}",
                    procedureName, e.getMessage());
        }
        return proc;
    }

    /**
     * SQL Server has no native table/column comments: SSMS stores them as the
     * {@code MS_Description} extended property. The JDBC driver does not surface those rows
     * through {@code getTables}/{@code getColumns} REMARKS, so a structure sync from SQL Server
     * would otherwise create a MySQL-family target (including OceanBase MySQL mode) without
     * comments even though that dialect can carry them.
     *
     * <p>The dictionary read is deliberately best-effort: an account without
     * {@code VIEW DEFINITION} may not see {@code sys.extended_properties}, and a missing
     * comment must never fail the metadata read. A blank property is treated like no
     * comment, matching the Oracle reader.
     */
    @Override
    protected TableMeta readTable(Connection conn, String schema, String tableName,
                                  boolean includeDetail, boolean includeForeignKeys)
            throws SQLException {
        TableMeta table = super.readTable(conn, schema, tableName, includeDetail,
                includeForeignKeys);
        if (table != null && includeDetail) {
            readComments(conn, schema, tableName, table);
        }
        return table;
    }

    /**
     * Reads {@code MS_Description} for the table and each of its columns in one dictionary
     * round trip. Kept package-private so the comment mapping can be tested without a live
     * SQL Server.
     *
     * <p>The first branch produces a row with a null column name for the table comment; the
     * second produces one row per commented column. A table with hundreds of columns still
     * costs one query, not one per column.
     */
    void readComments(Connection conn, String schema, String tableName, TableMeta table) {
        String schemaName = schemaPattern(schema);
        String sql = "SELECT comments.column_name, comments.comment_text FROM ("
                + "SELECT CAST(NULL AS NVARCHAR(128)) AS column_name, "
                + "CONVERT(NVARCHAR(MAX), ep.value) AS comment_text "
                + "FROM sys.objects o "
                + "JOIN sys.schemas s ON s.schema_id = o.schema_id "
                + "JOIN sys.extended_properties ep ON ep.class = 1 "
                + "AND ep.major_id = o.object_id AND ep.minor_id = 0 "
                + "AND ep.name = 'MS_Description' "
                + "WHERE s.name = ? AND o.name = ? AND o.type IN ('U','V') "
                + "UNION ALL "
                + "SELECT c.name, CONVERT(NVARCHAR(MAX), ep.value) "
                + "FROM sys.objects o "
                + "JOIN sys.schemas s ON s.schema_id = o.schema_id "
                + "JOIN sys.columns c ON c.object_id = o.object_id "
                + "JOIN sys.extended_properties ep ON ep.class = 1 "
                + "AND ep.major_id = c.object_id AND ep.minor_id = c.column_id "
                + "AND ep.name = 'MS_Description' "
                + "WHERE s.name = ? AND o.name = ? AND o.type IN ('U','V')"
                + ") comments";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            ps.setString(3, schemaName);
            ps.setString(4, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String comment = rs.getString(2);
                    if (comment == null || comment.isBlank()) {
                        continue;
                    }
                    if (name == null || name.isBlank()) {
                        table.setRemarks(comment);
                    } else {
                        table.column(name).ifPresent(col -> col.setRemarks(comment));
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("SQL Server comment read failed for {}: {}",
                    tableName, e.getMessage());
        }
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        // Sum over partitions of the heap/clustered index (index_id 0 or 1).
        String sql = "SELECT SUM(p.rows) FROM sys.partitions p "
                + "JOIN sys.tables t ON t.object_id = p.object_id "
                + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
                + "WHERE s.name = ? AND t.name = ? AND p.index_id IN (0,1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    if (!rs.wasNull() && rows > 0) {
                        return rows;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("sys.partitions estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }

    @Override
    public boolean reportsTableStats() {
        return true;
    }

    @Override
    public Map<String, MetadataReader.TableStat> estimateTableStats(Connection conn, String schema) {
        // One row per table: partition rows summed over index_id 0/1 (heap or clustered index,
        // which is the row count), pages summed over that partition's allocation units. The
        // allocation units are aggregated first: a partition usually owns more than one
        // (in-row, LOB, row-overflow), and joining them directly would count its rows once per
        // unit and report a table as several times larger than it is. The LEFT JOIN matters —
        // a table with no allocated pages yet still lists, with an unknown size rather than a
        // zero that would rank it as empty.
        return queryTableStats(conn,
                "SELECT t.name AS TABLE_NAME, SUM(p.rows) AS ROW_EST,"
                        + " SUM(a.total_pages) * 8192 AS TOTAL_BYTES"
                        + " FROM sys.tables t"
                        + " JOIN sys.schemas s ON s.schema_id = t.schema_id"
                        + " JOIN sys.partitions p ON p.object_id = t.object_id"
                        + " AND p.index_id IN (0, 1)"
                        + " LEFT JOIN (SELECT container_id, SUM(total_pages) AS total_pages"
                        + " FROM sys.allocation_units GROUP BY container_id) a"
                        + " ON a.container_id = p.partition_id"
                        + " WHERE s.name = ?"
                        + " GROUP BY t.name",
                schemaPattern(schema), true);
    }
}
