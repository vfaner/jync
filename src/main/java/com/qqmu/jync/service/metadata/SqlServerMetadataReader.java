package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
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
    public Map<String, MetadataReader.TableStat> estimateTableStats(Connection conn, String schema) {
        // One row per table: partition rows summed over index_id 0/1 (heap or clustered index,
        // which is the row count), pages summed over that partition's allocation units. The
        // LEFT JOIN matters — a table with no allocated pages yet still lists, with an unknown
        // size rather than a zero that would rank it as empty.
        return queryTableStats(conn,
                "SELECT t.name AS TABLE_NAME, SUM(p.rows) AS ROW_EST,"
                        + " SUM(a.total_pages) * 8192 AS TOTAL_BYTES"
                        + " FROM sys.tables t"
                        + " JOIN sys.schemas s ON s.schema_id = t.schema_id"
                        + " JOIN sys.partitions p ON p.object_id = t.object_id"
                        + " AND p.index_id IN (0, 1)"
                        + " LEFT JOIN sys.allocation_units a ON a.container_id = p.partition_id"
                        + " WHERE s.name = ?"
                        + " GROUP BY t.name",
                schemaPattern(schema));
    }
}
