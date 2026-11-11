package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.Locale;

/**
 * The target table a CDC event must be applied to no longer exists.
 *
 * <p>This is deliberately its own fault rather than a generic {@link SQLException}: an
 * absent table never heals by reconnecting. Classified as {@code TRANSIENT}, it made the
 * consumer back off, reconnect and replay the same transaction forever — the panel only
 * ever showed "disconnected", with no hint that one table had disappeared. The consumer
 * recognises the fixed prefix (see {@link CdcFailureClassifier}) and can now recreate
 * exactly that table, reload it, and replay from the same position instead of re-loading
 * the whole project.
 */
public final class CdcTargetTableMissingException extends SQLException {

    /** Recognised by {@link CdcFailureClassifier}; survives driver/wrapper messages. */
    public static final String PREFIX = "cdc target table missing: ";

    private final String sourceTable;
    private final String targetTable;
    private final String targetSchema;

    public CdcTargetTableMissingException(String sourceTable, String targetTable,
                                          String targetSchema, SQLException cause) {
        super(PREFIX + "source table " + sourceTable + " cannot be applied to target "
                + qualified(targetSchema, targetTable) + " because that table does not exist"
                + describe(cause), cause);
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.targetSchema = targetSchema;
    }

    /** The source table whose events could not be applied (the project's table name). */
    public String sourceTable() {
        return sourceTable;
    }

    /** The target table name after the project's table mapping. */
    public String targetTable() {
        return targetTable;
    }

    /** The target schema the consumer writes to; {@code null} or blank when none is set. */
    public String targetSchema() {
        return targetSchema;
    }

    private static String qualified(String schema, String table) {
        return schema == null || schema.isBlank()
                ? orUnknown(table) : orUnknown(schema) + "." + orUnknown(table);
    }

    private static String orUnknown(String value) {
        return value == null ? "?" : value;
    }

    /** The vendor's wording, so the change log shows what the target actually said. */
    private static String describe(SQLException cause) {
        if (cause == null || cause.getMessage() == null) {
            return "";
        }
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        return " (the target reported: " + message + ")";
    }
}
