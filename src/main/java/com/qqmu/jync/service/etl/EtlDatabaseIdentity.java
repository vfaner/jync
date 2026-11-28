package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * Best-effort physical identity of an ETL endpoint, read from the database itself.
 *
 * <p>The configured host is not identity: the same instance can be reached as
 * {@code localhost}, a container/service name, a DNS alias, or a NAT address. Before an
 * {@code OVERWRITE} deletes rows, the runner asks both connections who they are. Only a
 * server identity that is unique enough to compare (MySQL server_uuid, PostgreSQL
 * system_identifier, Oracle DB_UNIQUE_NAME/SERVER_HOST+DB_NAME, SQL Server ServerName) plus
 * the current database/schema is used; an unavailable probe returns null and leaves the
 * existing configure-level guard in charge.
 */
final class EtlDatabaseIdentity {

    private EtlDatabaseIdentity() {
    }

    static Identity read(Connection connection, DatabaseConfig config) {
        if (connection == null || config == null) {
            return null;
        }
        DatabaseType type = config.effectiveType();
        if (type == null) {
            return null;
        }
        return switch (type.getFamily()) {
            case MYSQL -> mysql(connection);
            case POSTGRES -> postgres(connection);
            case ORACLE -> oracle(connection);
            case SQLSERVER -> sqlServer(connection);
            default -> h2OrNull(connection, type);
        };
    }

    /**
     * True only when both sides answered and their unique server, database and schema match.
     * Unknown identity stays false; the string/DNS guard in {@link EtlTaskValidation} still
     * applies before the run.
     */
    static boolean sameDatabase(Connection source, Connection target,
                                DatabaseConfig sourceConfig, DatabaseConfig targetConfig) {
        if (sourceConfig == null || targetConfig == null
                || sourceConfig.effectiveType() != targetConfig.effectiveType()) {
            return false;
        }
        Identity left = read(source, sourceConfig);
        Identity right = read(target, targetConfig);
        return left != null && right != null && left.sameAs(right);
    }

    record Identity(String server, String database, String schema) {
        boolean sameAs(Identity other) {
            return other != null
                    && server.equalsIgnoreCase(other.server)
                    && database.equalsIgnoreCase(other.database)
                    && schema.equalsIgnoreCase(other.schema);
        }
    }

    private static Identity mysql(Connection connection) {
        try {
            return query(connection,
                    "SELECT @@server_uuid, DATABASE(), COALESCE(SCHEMA(), DATABASE())");
        } catch (SQLException first) {
            // MariaDB versions without @@server_uuid still expose host/port; the pair is not
            // globally unique but is enough to recognize two aliases of one server.
            try {
                return query(connection,
                        "SELECT CONCAT(@@hostname, ':', @@port), DATABASE(), "
                                + "COALESCE(SCHEMA(), DATABASE())");
            } catch (SQLException ignored) {
                return null;
            }
        }
    }

    private static Identity postgres(Connection connection) {
        try {
            return query(connection,
                    "SELECT (SELECT system_identifier::text FROM pg_control_system()), "
                            + "current_database(), current_schema()");
        } catch (SQLException first) {
            try {
                return query(connection,
                        "SELECT CONCAT(COALESCE(inet_server_addr()::text, ''), ':', "
                                + "COALESCE(inet_server_port()::text, '')), "
                                + "current_database(), current_schema()");
            } catch (SQLException ignored) {
                return null;
            }
        }
    }

    private static Identity oracle(Connection connection) {
        try {
            return query(connection,
                    "SELECT COALESCE(NULLIF(SYS_CONTEXT('USERENV','DB_UNIQUE_NAME'), ''), "
                            + "SYS_CONTEXT('USERENV','SERVER_HOST') || '/' "
                            + "|| SYS_CONTEXT('USERENV','DB_NAME')), "
                            + "SYS_CONTEXT('USERENV','DB_NAME'), "
                            + "SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual");
        } catch (SQLException e) {
            return null;
        }
    }

    private static Identity sqlServer(Connection connection) {
        try {
            return query(connection,
                    "SELECT CONVERT(varchar(256), SERVERPROPERTY('ServerName')), "
                            + "DB_NAME(), SCHEMA_NAME()");
        } catch (SQLException e) {
            return null;
        }
    }

    private static Identity h2OrNull(Connection connection, DatabaseType type) {
        if (type != DatabaseType.H2) {
            return null;
        }
        try {
            return query(connection, "SELECT DATABASE(), DATABASE(), SCHEMA()");
        } catch (SQLException e) {
            return null;
        }
    }

    private static Identity query(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            String server = rs.getString(1);
            String database = rs.getString(2);
            String schema = rs.getString(3);
            if (server == null || server.isBlank()
                    || database == null || database.isBlank()
                    || schema == null || schema.isBlank()) {
                return null;
            }
            return new Identity(server, database, schema);
        }
    }
}
