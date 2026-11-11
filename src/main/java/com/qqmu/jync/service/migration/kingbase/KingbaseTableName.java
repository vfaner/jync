package com.qqmu.jync.service.migration.kingbase;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import com.qqmu.jync.service.migration.MigrationTargetNaming;

/**
 * Target table and schema names for a MongoDB → KingbaseES run.
 *
 * <p>PostgreSQL-family identifiers are limited to 63 bytes and are silently truncated by the
 * server, which would make the target unreachable by the name the run reports. A name that does
 * not fit is therefore refused here, before anything is dropped or created.
 */
public final class KingbaseTableName {

    /** NAMEDATALEN minus the terminator. */
    public static final int MAX_BYTES = 63;

    private KingbaseTableName() {
    }

    /** Source collection name to target table name, validated before anything is written. */
    public static String resolve(MigrationTargetNaming naming, String prefix, String suffix,
                                 String sourceName) {
        String name;
        switch (naming) {
            case PREFIX -> name = prefix + sourceName;
            case SUFFIX -> name = sourceName + suffix;
            default -> name = sourceName;
        }
        validate(name, "error.migration.options.tableName.invalid");
        return name;
    }

    /** The schema to use: the connection's configured one, or the server's current schema. */
    public static String resolveSchema(String configuredSchema, String currentSchema) {
        if (configuredSchema != null && !configuredSchema.isBlank()) {
            return configuredSchema.trim();
        }
        return currentSchema;
    }

    /**
     * Resolves the schema against the live connection. The driver is only asked when the
     * connection has no configured schema; an old driver that cannot answer yields null, which
     * the callers report as an invalid schema rather than guessing {@code public}.
     */
    public static String resolveSchema(String configuredSchema, Connection connection) {
        if (configuredSchema != null && !configuredSchema.isBlank()) {
            return configuredSchema.trim();
        }
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    public static void validateSchema(String schema) {
        validate(schema, "error.migration.options.schema.invalid");
    }

    /**
     * The first column name the server would silently truncate, or null when every name fits.
     * MongoDB field names become column names verbatim, so they need the same byte limit as
     * tables and schemas; the check runs before any DDL touches the target.
     */
    public static String firstColumnNameTooLong(List<String> columnNames) {
        for (String name : columnNames) {
            if (name != null && name.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                return name;
            }
        }
        return null;
    }

    private static void validate(String name, String errorKey) {
        if (name == null
                || name.isBlank()
                || name.indexOf('\u0000') >= 0
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(errorKey);
        }
    }
}
