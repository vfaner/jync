package com.qqmu.jync.service.sync;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Whether a rejected INSERT may have collided with an existing key — shared by the polling
 * path ({@link DataSyncService}) and CDC replay ({@code CdcEventApplier}) so the two can
 * never disagree about which failures are retryable.
 *
 * <p>23505 is the standard unique-violation state (DB2, H2, PostgreSQL); 23000 is what MySQL
 * (1062), SQL Server (2627/2601) and Oracle (ORA-00001) use — but the same state also
 * carries their foreign-key, CHECK and NOT NULL failures, so this is only a CANDIDATE test.
 * The caller decides by evidence: the retried UPDATE's row count, or a primary-key existence
 * probe for key-only tables. Anything that fails those checks is rethrown.
 */
public final class UniqueViolations {

    private UniqueViolations() {
    }

    public static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && ("23505".equals(state) || "23000".equals(state))) {
            return true;
        }
        String message = e.getMessage();
        return message != null
                && (message.toLowerCase(Locale.ROOT).contains("duplicate")
                        || message.toLowerCase(Locale.ROOT).contains("unique constraint"));
    }
}
