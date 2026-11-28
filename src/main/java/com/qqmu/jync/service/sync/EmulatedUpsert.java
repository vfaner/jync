package com.qqmu.jync.service.sync;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The UPDATE-then-INSERT fallback shared by polling and CDC targets without a native upsert.
 *
 * <p>The two engines read rows differently (a live result set vs a buffered transaction), but
 * the conflict rules must not diverge: a replayed row that already holds the incoming values
 * is a no-op, while a unique violation on some <em>other</em> constraint must surface instead
 * of advancing the position past a row that was never written. Keeping that decision here
 * means a fix to the edge cases lands in both paths.
 */
public final class EmulatedUpsert {

    public enum Outcome { UPDATED, INSERTED, NOOP }

    /** Which bind layout the caller must apply to a prepared statement. */
    public enum StatementKind { UPDATE, INSERT, EXISTS }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement, StatementKind kind) throws SQLException;
    }

    private EmulatedUpsert() {
    }

    /**
     * Applies one row. The caller owns the statements and the row's LOB lifetime; the binder
     * is invoked again after a unique violation, so every value it binds must be replayable.
     */
    public static Outcome apply(PreparedStatement insert, PreparedStatement update,
                                PreparedStatement exists, boolean guardUnchanged,
                                boolean probeExistsAfterRetry, Binder binder)
            throws SQLException {
        int updated = 0;
        if (update != null) {
            binder.bind(update, StatementKind.UPDATE);
            updated = update.executeUpdate();
        }
        if (updated != 0) {
            return Outcome.UPDATED;
        }
        if (guardUnchanged && exists != null && exists(exists, binder)) {
            return Outcome.NOOP;
        }

        binder.bind(insert, StatementKind.INSERT);
        try {
            return insert.executeUpdate() == 0 ? Outcome.NOOP : Outcome.INSERTED;
        } catch (SQLException e) {
            if (!UniqueViolations.isUniqueViolation(e)) {
                throw e;
            }
            if (update != null) {
                // A concurrent writer inserted the same key between our UPDATE and INSERT.
                // Retry the update; zero rows is still ambiguous when the guard is on.
                binder.bind(update, StatementKind.UPDATE);
                if (update.executeUpdate() != 0) {
                    return Outcome.UPDATED;
                }
                // A driver that reports changed rows answers 0 for a re-delivered unchanged
                // row. Whether that ambiguity applies without the SQL guard is a per-engine
                // decision (CDC has always probed; polling only does with the guard).
                if (probeExistsAfterRetry && exists != null && exists(exists, binder)) {
                    return Outcome.NOOP;
                }
                throw e;
            }
            // Key-only table: the rejected insert is a no-op only when the key is present.
            if (exists != null && exists(exists, binder)) {
                return Outcome.NOOP;
            }
            throw e;
        }
    }

    private static boolean exists(PreparedStatement statement, Binder binder)
            throws SQLException {
        binder.bind(statement, StatementKind.EXISTS);
        try (ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }
}
