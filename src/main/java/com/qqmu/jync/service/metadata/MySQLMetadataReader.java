package com.qqmu.jync.service.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL-family reader: MySQL, MariaDB, OceanBase (MySQL mode), TiDB and GBase, which all
 * speak the MySQL protocol and share its catalog layout.
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
                || type == DatabaseType.GBASE
                || type == DatabaseType.OCEANBASE
                || type == DatabaseType.TIDB
                || type == DatabaseType.GOLDENDB
                || type == DatabaseType.TDSQL
                || type == DatabaseType.POLARDB
                || type == DatabaseType.GREATDB
                || type == DatabaseType.UPDRDB
                || type == DatabaseType.KUNDB;
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
            MetadataTimeouts.apply(ps);
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
            MetadataTimeouts.apply(ps);
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
            MetadataTimeouts.apply(ps);
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

    /**
     * {@inheritDoc}
     *
     * <p>{@code SHOW GRANTS} is readable by the account itself and names every grant, so a
     * schema-wide ({@code ON *.*} or {@code ON db.*}) SELECT proves the listing is not
     * filtered. A table-level grant is the hazardous case — it can be revoked, and the
     * table then disappears from {@code SHOW FULL TABLES} exactly as a drop would — so it
     * never counts. Grants inherited through roles are not expanded here; the answer then
     * stays conservative.
     */
    @Override
    public boolean schemaListingIsComplete(Connection conn, String schema) {
        // A plain Statement: SHOW cannot be server-prepared, and some MySQL-family drivers
        // reject it through prepareStatement.
        try (Statement st = conn.createStatement()) {
            MetadataTimeouts.apply(st);
            try (ResultSet rs = st.executeQuery("SHOW GRANTS")) {
                while (rs.next()) {
                    if (grantCoversWholeSchema(rs.getString(1), schema)) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read the MySQL grants; source-side drops stay conservative: {}",
                    e.getMessage());
        }
        return false;
    }

    /** Whether one {@code SHOW GRANTS} line grants SELECT over the whole schema. */
    static boolean grantCoversWholeSchema(String grant, String schema) {
        if (grant == null) {
            return false;
        }
        String line = grant.trim();
        String upper = line.toUpperCase(java.util.Locale.ROOT);
        int on = upper.indexOf(" ON ");
        int to = upper.indexOf(" TO ");
        // A role assignment ("GRANT `r`@`%` TO ...") carries no ON clause.
        if (!upper.startsWith("GRANT ") || on < 0 || to <= on) {
            return false;
        }
        if (!grantsSelect(upper.substring("GRANT ".length(), on))) {
            return false;
        }
        String target = line.substring(on + " ON ".length(), to).trim();
        if (!target.endsWith(".*")) {
            return false;                       // table- or column-scoped grant
        }
        String scope = target.substring(0, target.length() - 2).trim();
        if (scope.equals("*")) {
            return true;                        // global grant
        }
        if (scope.length() >= 2 && scope.startsWith("`") && scope.endsWith("`")) {
            scope = scope.substring(1, scope.length() - 1).replace("``", "`");
        }
        return scope.equalsIgnoreCase(schema);
    }

    private static boolean grantsSelect(String privileges) {
        for (String privilege : privileges.split(",")) {
            String token = privilege.trim();
            if (token.equals("SELECT") || token.equals("ALL") || token.equals("ALL PRIVILEGES")) {
                return true;
            }
        }
        return false;
    }
}
