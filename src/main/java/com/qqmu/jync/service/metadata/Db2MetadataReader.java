package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/** DB2 reader. Bodies come from {@code SYSCAT.VIEWS} and {@code SYSCAT.ROUTINES}. */
@Component
@Slf4j
public class Db2MetadataReader extends GenericMetadataReader {

    @Override
    public boolean supports(DatabaseType type) {
        return type == DatabaseType.DB2;
    }

    @Override
    protected String catalogFor(Connection conn, String schema) {
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Conservative on purpose: {@code SYSCAT} views are granted to PUBLIC but their rows
     * are filtered by the account's authorities, and telling DBADM/SYSADM apart from a
     * table-level-grant account would need an authority probe this reader cannot verify
     * against every DB2 edition. The inherited {@code false} keeps the target table.
     */
    @Override
    public boolean schemaListingIsComplete(Connection conn, String schema) {
        return false;
    }

    @Override
    protected String schemaPattern(String schema) {
        // DB2 folds unquoted identifiers to upper case.
        return schema == null || schema.isBlank() ? null : schema.toUpperCase(Locale.ROOT);
    }

    @Override
    public String resolveDefaultSchema(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CURRENT SCHEMA FROM SYSIBM.SYSDUMMY1")) {
            MetadataTimeouts.apply(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    if (s != null && !s.isBlank()) {
                        return s.trim();
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("CURRENT SCHEMA lookup failed: {}", e.getMessage());
        }
        return super.resolveDefaultSchema(conn);
    }

    @Override
    public ViewMeta readView(Connection conn, String schema, String viewName) throws SQLException {
        ViewMeta view = new ViewMeta();
        view.setName(viewName);
        view.setSchema(schema);
        String sql = "SELECT TEXT FROM SYSCAT.VIEWS WHERE VIEWSCHEMA = COALESCE(?, CURRENT SCHEMA) "
                + "AND VIEWNAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, viewName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    view.setDefinition(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read DB2 view text for {}: {}", viewName, e.getMessage());
        }
        return view;
    }

    @Override
    public ProcedureMeta readProcedure(Connection conn, String schema, String procedureName) throws SQLException {
        ProcedureMeta proc = super.readProcedure(conn, schema, procedureName);
        String sql = "SELECT TEXT, ROUTINETYPE FROM SYSCAT.ROUTINES "
                + "WHERE ROUTINESCHEMA = COALESCE(?, CURRENT SCHEMA) AND ROUTINENAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, procedureName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    proc.setDefinition(rs.getString("TEXT"));
                    // ROUTINETYPE is 'P' for procedures and 'F' for functions.
                    proc.setRoutineType("F".equalsIgnoreCase(trim(rs.getString("ROUTINETYPE")))
                            ? "FUNCTION" : "PROCEDURE");
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read DB2 routine text for {}: {}", procedureName, e.getMessage());
        }
        return proc;
    }

    @Override
    public long estimateRowCount(Connection conn, String schema, String tableName) throws SQLException {
        String sql = "SELECT CARD FROM SYSCAT.TABLES WHERE TABSCHEMA = COALESCE(?, CURRENT SCHEMA) "
                + "AND TABNAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            MetadataTimeouts.apply(ps);
            ps.setString(1, schemaPattern(schema));
            ps.setString(2, tableName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long rows = rs.getLong(1);
                    // CARD is -1 when statistics have never been collected.
                    if (!rs.wasNull() && rows > 0) {
                        return rows;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("SYSCAT.TABLES estimate failed for {}: {}", tableName, e.getMessage());
        }
        return super.estimateRowCount(conn, schema, tableName);
    }

    @Override
    public Map<String, MetadataReader.TableStat> estimateTableStats(Connection conn, String schema) {
        // SYSCAT.TABLES.CARD is the statistics row count (-1 until RUNSTATS). DB2 has no
        // per-table byte total in this view without joining tablespaces, so the size stays
        // unknown and the planner bins by rows alone — which is what it does everywhere.
        return queryTableStats(conn,
                "SELECT TABNAME AS TABLE_NAME, CARD AS ROW_EST,"
                        + " CAST(NULL AS BIGINT) AS TOTAL_BYTES"
                        + " FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TYPE = 'T'",
                schemaPattern(schema), true);
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}
