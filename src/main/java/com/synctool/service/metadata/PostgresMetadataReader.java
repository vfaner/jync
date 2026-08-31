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
 * PostgreSQL reader, also used for OpenGauss, 人大金仓 (KingBase) and 神通 (Oscar), which
 * are all PostgreSQL-derived and keep the same catalog layout.
 */
@Component
@Slf4j
public class PostgresMetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.POSTGRESQL
                || type == DatabaseType.OPENGAUSS
                || type == DatabaseType.KINGBASE
                || type == DatabaseType.OSCAR;
    }

    @Override
    protected String schemaPattern(String schema) {
        return schema == null || schema.isBlank() ? "public" : schema;
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT current_schema()");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String schema = rs.getString(1);
                if (schema != null && !schema.isBlank()) {
                    return schema;
                }
            }
        } catch (SQLException e) {
            log.debug("current_schema() failed: {}", e.getMessage());
        }
        return "public";
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        // pg_get_viewdef reproduces the normalized body the server actually stores.
        String sql = "SELECT pg_get_viewdef(c.oid, true) FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('v','m')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    view.setDefinition(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read PostgreSQL view definition for {}: {}", viewName, e.getMessage());
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);
        // prokind: 'f' function, 'p' procedure, 'a' aggregate, 'w' window.
        String sql = "SELECT p.prosrc, pg_get_function_result(p.oid) AS result_type, "
                + "pg_get_functiondef(p.oid) AS full_def, "
                + "CASE WHEN p.prokind = 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END AS routine_type "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = ? AND p.proname = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, procedureName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setRoutineType(rs.getString("routine_type"));
                    proc.setReturnType(rs.getString("result_type"));
                    // Prefer the complete CREATE statement; it carries the language and volatility.
                    String fullDef = rs.getString("full_def");
                    proc.setDefinition(fullDef != null ? fullDef : rs.getString("prosrc"));
                }
            }
        } catch (SQLException e) {
            // Older PostgreSQL and some forks lack prokind; retry without it.
            log.debug("pg_proc query with prokind failed ({}), retrying legacy form", e.getMessage());
            readProcedureLegacy(conn, schema, procedureName, proc);
        }
        return proc;
    }

    private void readProcedureLegacy(Connection conn, String schema, String procedureName,
                                     ProcedureMeta proc) {
        String sql = "SELECT p.prosrc, pg_get_function_result(p.oid) AS result_type "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = ? AND p.proname = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, procedureName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setRoutineType("FUNCTION");
                    proc.setReturnType(rs.getString("result_type"));
                    proc.setDefinition(rs.getString("prosrc"));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read routine source for {}: {}", procedureName, e.getMessage());
        }
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        String sql = "SELECT c.reltuples::bigint FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    // reltuples is -1 on a never-analyzed table.
                    if (!rs.wasNull() && rows > 0) {
                        return rows;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("reltuples estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }
}
