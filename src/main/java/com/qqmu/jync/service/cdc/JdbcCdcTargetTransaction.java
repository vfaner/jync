package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.util.JdbcUtil;

/**
 * The JDBC target transaction the CDC consumers have always run, now in one place: borrow
 * one pooled connection, turn auto-commit off, apply the transaction's rows (upserts before
 * deletes), commit once, and give the connection back.
 *
 * <p>Both the lease re-check and the position advance happen after this handle is closed.
 * A crash in between replays the transaction against idempotent upserts (at-least-once).
 *
 * <p>The applier is deliberately transaction-dumb: this class owns begin/commit/rollback,
 * so one source transaction maps to exactly one target commit.
 */
public final class JdbcCdcTargetTransaction implements CdcTargetTransaction {

    private final Connection connection;
    private final CdcEventApplier applier;
    private final boolean originalAutoCommit;
    private boolean committed;

    public JdbcCdcTargetTransaction(SyncContext ctx, CdcRuntimeStats stats,
                                    int queryTimeoutSeconds) throws SQLException {
        this.applier = new CdcEventApplier(ctx, stats, queryTimeoutSeconds);
        Connection opened = ctx.getConnectionProvider().open(ctx.getTargetConfig());
        boolean original = true;
        try {
            original = opened.getAutoCommit();
            opened.setAutoCommit(false);
        } catch (SQLException | RuntimeException e) {
            JdbcUtil.rollbackAndClose(opened);
            throw e;
        }
        this.connection = opened;
        this.originalAutoCommit = original;
    }

    @Override
    public void applyUpserts(CdcTableBinding binding, List<Map<String, Object>> rows)
            throws SQLException {
        applier.applyUpserts(connection, binding, rows);
    }

    @Override
    public void applyDeletes(CdcTableBinding binding, List<Map<String, Object>> rows)
            throws SQLException {
        applier.applyDeletes(connection, binding, rows);
    }

    @Override
    public void commit() throws SQLException {
        connection.commit();
        committed = true;
    }

    @Override
    public void close() {
        if (!committed) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The failure that brought us here is the one worth reporting; a broken
                // connection cannot be rolled back, and closing it still returns the seat.
            }
        }
        try {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // The connection may already be closed or broken; close() below is the point.
        }
        // 归还池连接：漏掉它，每个已提交事务都会永久占走一个目标池连接。
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing left to return.
        }
    }
}
