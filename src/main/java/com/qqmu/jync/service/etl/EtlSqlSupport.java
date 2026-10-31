package com.qqmu.jync.service.etl;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.util.JdbcUtil;

/**
 * The few SQL-shape rules the precheck and the runner must agree on.
 *
 * <p>They are shared on purpose: a precheck that accepts what the run then rejects is worse than
 * no precheck, because it moves the customer's trust in the wrong direction.
 */
final class EtlSqlSupport {

    /** Label used for the wrapper around the customer's extraction SQL. */
    static final String SUBQUERY_ALIAS = "jync_etl_src";

    /** Targets are created and written unquoted, so a result label must survive folding. */
    static final Pattern PLAIN_IDENTIFIER =
            Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]*");

    static final Pattern RESUME_KEY =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EtlSqlSupport() {
    }

    static String stripTrailingSemicolon(String sql) {
        return JdbcUtil.stripTrailingSemicolon(sql);
    }

    /** Wraps the extraction SQL so its columns can be read without returning rows. */
    static String zeroRowProbe(String sql) {
        return "SELECT * FROM (" + stripTrailingSemicolon(sql) + ") "
                + SUBQUERY_ALIAS + " WHERE 1 = 0";
    }

    static String qualified(DatabaseConfig targetConfig, String configuredTable) {
        String table = configuredTable.trim();
        if (table.contains(".")) {
            return table;
        }
        String schema = targetConfig.getSchemaName();
        if (schema != null && !schema.isBlank()) {
            return schema.trim() + "." + table;
        }
        return table;
    }

    static boolean exists(Connection target, String qualifiedTable) {
        return exists(target, qualifiedTable, 0);
    }

    /**
     * @param timeoutSeconds {@code > 0} bounds the probe. A precheck must not wait forever
     *                       behind a metadata lock that the load itself would only meet after
     *                       an operator has already decided to run it.
     */
    static boolean exists(Connection target, String qualifiedTable, int timeoutSeconds) {
        try (Statement probe = target.createStatement()) {
            if (timeoutSeconds > 0) {
                probe.setQueryTimeout(timeoutSeconds);
            }
            probe.executeQuery("SELECT * FROM " + qualifiedTable + " WHERE 1 = 0").close();
            return true;
        } catch (SQLException e) {
            // PostgreSQL-family drivers abort the whole transaction when any statement fails.
            // The probe for a missing table is expected to fail, so clear that state before
            // the caller's CREATE TABLE; nothing has been written to the target at this point.
            rollbackQuietly(target);
            return false;
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Auto-commit connections refuse a rollback; there is nothing to undo.
        }
    }
}
