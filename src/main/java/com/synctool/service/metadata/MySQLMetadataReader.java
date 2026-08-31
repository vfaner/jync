package com.synctool.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import com.synctool.dto.meta.ProcedureMeta;
import com.synctool.dto.meta.ViewMeta;
import com.synctool.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL / MariaDB reader.
 *
 * <p>MySQL exposes the database as the JDBC <em>catalog</em> and leaves schema null, so the
 * catalog/schema pairing is inverted relative to the generic reader. View and routine
 * bodies come from {@code information_schema}.
 */
@Component
@Slf4j
public class MySQLMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.MYSQL
                || type == DatabaseType.MARIADB
                || type == DatabaseType.GBASE;
    }

    @Override
    protected String catalogFor(Connection conn, String schema) {
        // In MySQL the "schema" the user configured is really the catalog.
        return schema == null || schema.isBlank() ? currentCatalog(conn) : schema;
    }

    @Override
    protected String schemaPattern(String schema) {
        return null;
    }

    private String currentCatalog(Connection conn) {
        try {
            return conn.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        String catalog = conn.getCatalog();
        return catalog != null && !catalog.isBlank() ? catalog : super.resolveDefaultSchema(conn);
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        String sql = "SELECT VIEW_DEFINITION FROM information_schema.VIEWS "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, blankToNull(schema));
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    view.setDefinition(rs.getString(1));
                }
            }
        }
        if (view.getDefinition() == null) {
            log.debug("No definition found for MySQL view {}", viewName);
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);
        String sql = "SELECT ROUTINE_TYPE, ROUTINE_DEFINITION, DTD_IDENTIFIER "
                + "FROM information_schema.ROUTINES "
                + "WHERE ROUTINE_SCHEMA = COALESCE(?, DATABASE()) AND ROUTINE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, blankToNull(schema));
            ps.setString(2, procedureName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setRoutineType(rs.getString("ROUTINE_TYPE"));
                    proc.setDefinition(rs.getString("ROUTINE_DEFINITION"));
                    proc.setReturnType(rs.getString("DTD_IDENTIFIER"));
                }
            }
        }
        return proc;
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        // TABLE_ROWS is an estimate for InnoDB but avoids a full scan on large tables.
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, blankToNull(schema));
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
            log.debug("information_schema row estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
