package com.qqmu.jync.service;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Cancellation handle for one exact row-count job.
 *
 * <p>Interrupting the worker thread is not enough: JDBC drivers do not promise to notice an
 * interrupt while a statement is waiting on the server. The service registers each live
 * {@code COUNT(*)} statement here, and {@link #cancel()} calls {@link Statement#cancel()} so
 * the source/target stops the scan instead of running until the query timeout. Drivers that
 * ignore {@code cancel()} still hit that timeout; this is best-effort by definition.
 */
@Slf4j
public final class RowCountControl {

    private final Set<Statement> statements = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }

    /** Register before execute; a cancel that already happened is applied immediately. */
    public void register(Statement statement) {
        if (statement == null) {
            return;
        }
        statements.add(statement);
        if (cancelled) {
            cancelQuietly(statement);
        }
    }

    public void unregister(Statement statement) {
        if (statement != null) {
            statements.remove(statement);
        }
    }

    public void cancel() {
        cancelled = true;
        for (Statement statement : statements) {
            cancelQuietly(statement);
        }
    }

    private void cancelQuietly(Statement statement) {
        try {
            statement.cancel();
        } catch (SQLException e) {
            // A closed statement or a driver without cancel support is not an error worth
            // surfacing: the job is already marked cancelled and the query timeout remains.
            log.debug("Row-count statement cancel failed: {}", e.toString());
        }
    }
}
