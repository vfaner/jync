package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Oracle-family reader: Oracle, 达梦 (DM) and 崖山 (YashanDB), which keep the same
 * dictionary layout ({@code ALL_VIEWS}, {@code ALL_SOURCE}, {@code ALL_OBJECTS}).
 *
 * <p>Oracle has no catalogs, so the catalog argument is always null and the schema is the
 * owner. View text lives in {@code ALL_VIEWS.TEXT} and routine source in
 * {@code ALL_SOURCE}, which must be reassembled line by line.
 */
@Component
@Slf4j
public class OracleMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.ORACLE
                || type == DatabaseType.DM
                || type == DatabaseType.YASHANDB;
    }

    @Override
    protected String catalogFor(Connection conn, String schema) {
        return null;
    }

    @Override
    protected String schemaPattern(String schema) {
        // Oracle stores unquoted identifiers folded to upper case.
        return schema == null || schema.isBlank() ? null : schema.toUpperCase();
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.debug("SELECT USER failed: {}", e.getMessage());
        }
        return super.resolveDefaultSchema(conn);
    }

    /**
     * Adds the {@code ROWDEPENDENCIES} probe to the generic table read.
     *
     * <p>Only real Oracle is whitelisted: 达梦 and 崖山 mimic the dictionary layout (this
     * reader serves all three), but {@code ORA_ROWSCN} row-level semantics are an Oracle
     * guarantee, so on the look-alikes the flag stays null and the cursor resolver never
     * offers the SCN strategy. The probe failure is non-fatal for the same reason — an
     * unreadable dictionary must not fail the whole metadata read.
     */
    @Override
    public TableMeta readTable(Connection conn, String schema, String tableName) throws SQLException {
        TableMeta table = super.readTable(conn, schema, tableName);
        if (table != null && isOracleProduct(conn)) {
            table.setRowLevelScn(probeRowDependencies(conn, schema, tableName));
        }
        return table;
    }

    private boolean isOracleProduct(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase().contains("oracle");
        } catch (SQLException e) {
            log.debug("Could not read database product name: {}", e.getMessage());
            return false;
        }
    }

    /** {@code ALL_TABLES.DEPENDENCIES}: ENABLED = row-level SCN, DISABLED = block-level. */
    private Boolean probeRowDependencies(Connection conn, String schema, String tableName) {
        String sql = "SELECT DEPENDENCIES FROM ALL_TABLES "
                + "WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "ENABLED".equalsIgnoreCase(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.debug("ROWDEPENDENCIES probe failed for {}: {}", tableName, e.getMessage());
        }
        return null;
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        String sql = "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = NVL(?, USER) AND VIEW_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, viewName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // TEXT is LONG; the driver surfaces it as a string here.
                    view.setDefinition(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read Oracle view text for {}: {}", viewName, e.getMessage());
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);

        String typeSql = "SELECT OBJECT_TYPE FROM ALL_OBJECTS "
                + "WHERE OWNER = NVL(?, USER) AND OBJECT_NAME = ? "
                + "AND OBJECT_TYPE IN ('PROCEDURE','FUNCTION')";
        try (PreparedStatement ps = conn.prepareStatement(typeSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, procedureName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setRoutineType(rs.getString(1));
                }
            }
        }

        // ALL_SOURCE holds one row per source line; LINE ordering must be preserved.
        String srcSql = "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = NVL(?, USER) "
                + "AND NAME = ? AND TYPE = ? ORDER BY LINE";
        StringBuilder body = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(srcSql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, procedureName.toUpperCase());
            ps.setString(3, proc.getRoutineType() == null ? "PROCEDURE" : proc.getRoutineType());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1);
                    if (line != null) {
                        body.append(line);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read Oracle source for {}: {}", procedureName, e.getMessage());
        }
        if (body.length() > 0) {
            proc.setDefinition(body.toString());
        }
        return proc;
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        String sql = "SELECT NUM_ROWS FROM ALL_TABLES WHERE OWNER = NVL(?, USER) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, upperOrNull(schema));
            ps.setString(2, tableName.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    // NUM_ROWS is null until statistics have been gathered.
                    if (!rs.wasNull() && rows > 0) {
                        return rows;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ALL_TABLES estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }

    private String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.toUpperCase();
    }
}
