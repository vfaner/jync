package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * Opens pooled connections to a saved database configuration.
 *
 * <p>Deep write paths that need more than the cycle's one connection per side — the parallel
 * full-load workers — take theirs from the context instead of knowing about Spring wiring,
 * the same way {@code lockRenewer} is carried. May be {@code null} in unit tests; features
 * needing extra connections must degrade to sequential then.
 */
@FunctionalInterface
public interface ConnectionProvider {

    Connection open(DatabaseConfig config) throws SQLException;

    /**
     * Opens a connection for a chunk worker, which shares its pool with every other task on
     * the same configuration and may have to wait for a seat or defer the run.
     *
     * <p>Defaults to {@link #open}: a provider that cannot queue keeps the old behaviour.
     * The production wiring overrides it with the waiting borrow; the CDC consumers keep
     * calling {@code open}, because a long-lived apply connection must not be silently
     * skipped.
     */
    default Connection openWaiting(DatabaseConfig config) throws SQLException {
        return open(config);
    }
}
