package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;

import lombok.extern.slf4j.Slf4j;

/**
 * The transaction a streamed source read runs in.
 *
 * <p>PostgreSQL's driver buffers the entire result set when autocommit is on, so a fetch
 * size only means "read a page at a time" inside an explicit transaction. This session
 * opens that transaction around one table copy and closes it afterwards.
 *
 * <p>It is deliberately reversible and quiet:
 * <ul>
 *   <li>A connection that already has autocommit off belongs to its caller — the session
 *       neither commits it nor restores anything.</li>
 *   <li>The read has no side effects, so a failed commit (or a failed autocommit restore)
 *       must not fail a copy whose rows are already applied to the target; it is logged and
 *       the copy carries on.</li>
 *   <li>{@link #close()} rolls back whatever was never committed and restores the original
 *       autocommit mode on every path, including failures.</li>
 * </ul>
 */
@Slf4j
public final class SourceReadSession implements AutoCloseable {

    private static final SourceReadSession NONE = new SourceReadSession(null, false);

    private final Connection connection;
    /** True when this session turned autocommit off and therefore owns the transaction. */
    private final boolean opened;
    /** True until {@link #commit()} or {@link #rollback()} ends the transaction. */
    private boolean active;
    /** Restores autocommit exactly once, however many times close is called. */
    private boolean closed;

    private SourceReadSession(Connection connection, boolean opened) {
        this.connection = connection;
        this.opened = opened;
        this.active = opened;
    }

    /**
     * Opens the read transaction when {@code required}; otherwise returns a no-op session
     * that never touches the connection.
     */
    public static SourceReadSession begin(Connection connection, boolean required)
            throws SQLException {
        if (!required || connection == null) {
            return NONE;
        }
        if (!connection.getAutoCommit()) {
            // The caller is already inside a transaction of its own: leave it alone.
            return NONE;
        }
        connection.setAutoCommit(false);
        return new SourceReadSession(connection, true);
    }

    /** Whether this session actually turned autocommit off. */
    public boolean opened() {
        return opened;
    }

    /** Ends the read on success. Never throws: the rows have already been written. */
    public void commit() {
        if (!opened || !active) {
            return;
        }
        active = false;
        try {
            connection.commit();
        } catch (SQLException e) {
            log.warn("Could not commit the source read transaction: {}", e.getMessage());
            rollbackQuietly();
        }
    }

    /** Ends the read after a failure. Never throws. */
    public void rollback() {
        if (opened) {
            active = false;
            rollbackQuietly();
        }
    }

    /** Rolls back a transaction that was never committed, then restores autocommit. */
    @Override
    public void close() {
        if (!opened || closed) {
            return;
        }
        closed = true;
        if (active) {
            active = false;
            rollbackQuietly();
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Could not restore source autocommit: {}", e.getMessage());
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Could not roll back the source read transaction: {}", e.getMessage());
        }
    }
}
