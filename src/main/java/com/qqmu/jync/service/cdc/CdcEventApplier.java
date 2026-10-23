package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.lob.LobBinder;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.util.CollectionUtil;
import com.qqmu.jync.util.JdbcUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies buffered CDC row changes to the target through the same dialect SQL the
 * polling write engine uses (task book B5): idempotent upserts for INSERT/UPDATE
 * after-images, delete-by-PK batches for DELETE events.
 *
 * <p>Idempotency is the whole point — at-least-once delivery replays the overlap window,
 * so a row already present must converge instead of colliding. Targets whose dialect has
 * no single-statement upsert ({@link GenericSqlDialect}) get the same update-then-insert
 * emulation the polling engine uses; every other dialect's native upsert is batched.
 *
 * <p>Deliberately transaction-dumb: no setAutoCommit, no commit, no rollback. The
 * consumer owns the transaction so that one XID (binlog transaction boundary) maps to
 * exactly one target commit followed by one position persist — data first, position
 * second, the contract that makes at-least-once replay safe.
 */
@Slf4j
public final class CdcEventApplier {

    private final SyncContext ctx;
    private final CdcRuntimeStats stats;

    /**
     * Bound on how long one apply statement may run, in seconds; {@code <= 0} disables it.
     * Same knob and same semantics as the polling write engine (SyncProperties
     * {@code query-timeout-seconds} via {@link JdbcUtil#applyQueryTimeout}), so a locked
     * target table cannot park the consumer thread — and the whole CDC pipeline behind
     * it — indefinitely.
     */
    private final int queryTimeoutSeconds;

    /** No-timeout convenience overload; existing call sites keep their behavior. */
    public CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats) {
        this(ctx, stats, 0);
    }

    public CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats, int queryTimeoutSeconds) {
        this.ctx = ctx;
        this.stats = stats;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public void applyUpserts(Connection targetConn, CdcTableBinding binding,
                             List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        cleanse(binding, rows);
        // Same dispatch as the polling engine: emulate only when there is a key to update
        // by; a keyless binding (unreachable in CDC — the handler gates on isApplicable)
        // degrades to the plain-INSERT branch rather than an update with an empty WHERE.
        if (!binding.pkNames().isEmpty()
                && ctx.getTargetDialect() instanceof GenericSqlDialect
                && !((GenericSqlDialect) ctx.getTargetDialect()).supportsNativeUpsert()) {
            applyEmulatedUpserts(targetConn, binding, rows,
                    (GenericSqlDialect) ctx.getTargetDialect());
        } else {
            applyNativeUpserts(targetConn, binding, rows);
        }
        // Per-table accumulation also feeds the global rows-applied total.
        stats.addTableUpserts(binding.getTable(), rows.size());
    }

    public void applyDeletes(Connection targetConn, CdcTableBinding binding,
                             List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        // A cleansed primary key must be transformed the same way the upsert was, or the
        // delete would look for a row the target does not hold.
        cleanse(binding, rows);
        List<String> pkColumns = binding.pkNames();
        String sql = ctx.getTargetDialect().getDeleteByPkSql(
                ctx.getTargetSchema(), binding.getTargetTable(), pkColumns);
        Map<String, Integer> types = binding.typeByColumn(ctx.getTargetDialect(), ctx.effectiveSourceType());
        try (PreparedStatement ps = targetConn.prepareStatement(sql)) {
            JdbcUtil.applyQueryTimeout(ps, queryTimeoutSeconds);
            int inBatch = 0;
            for (Map<String, Object> row : rows) {
                JdbcRowMapper.bind(ps, row, pkColumns, types, ctx.getTargetDialect());
                ps.addBatch();
                if (++inBatch >= ctx.getBatchSize()) {
                    ps.executeBatch();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                ps.executeBatch();
            }
        }
        stats.addTableDeletes(binding.getTable(), rows.size());
    }

    /**
     * Applies the project's column rules to the buffered rows in place.
     *
     * <p>In-place is safe under at-least-once because every consumer drops its buffer when
     * an apply fails and replays the transaction by re-reading the source stream, so the
     * same row map is never cleansed twice. A future retry path that re-applies the SAME
     * list must copy first: non-idempotent rules such as STRIP_PREFIX would otherwise run
     * twice on values that are already clean.
     */
    private void cleanse(CdcTableBinding binding, List<Map<String, Object>> rows) {
        ColumnCleanser cleanser = ColumnCleanser.forTable(ctx.getConfig(), binding.getTable());
        for (Map<String, Object> row : rows) {
            cleanser.apply(row);
        }
    }

    /** The normal path: one idempotent statement per row, executed in JDBC batches. */
    private void applyNativeUpserts(Connection targetConn, CdcTableBinding binding,
                                    List<Map<String, Object>> rows) throws SQLException {
        List<String> columns = binding.columnNames();
        List<String> pkColumns = binding.pkNames();
        Map<String, Integer> types = binding.typeByColumn(ctx.getTargetDialect(), ctx.effectiveSourceType());
        String sql = ctx.getTargetDialect().getUpsertSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, pkColumns, types);
        List<String> bindOrder = ctx.getTargetDialect().upsertBindOrder(columns, pkColumns);
        // Temporary LOBs live until the batch is executed; they are freed right after.
        LobBinder.Tracker lobTracker = LobBinder.newTracker();
        try (PreparedStatement ps = targetConn.prepareStatement(sql)) {
            JdbcUtil.applyQueryTimeout(ps, queryTimeoutSeconds);
            int inBatch = 0;
            for (Map<String, Object> row : rows) {
                JdbcRowMapper.bind(ps, row, bindOrder, types, ctx.getTargetDialect(), lobTracker);
                ps.addBatch();
                if (++inBatch >= ctx.getBatchSize()) {
                    ps.executeBatch();
                    lobTracker.releaseAll();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                ps.executeBatch();
            }
        } finally {
            lobTracker.releaseAll();
        }
    }

    /**
     * Fallback for targets without a native upsert, mirroring the polling engine: try
     * UPDATE by key, INSERT only when the update matched nothing. Per-row rather than
     * batched — the update's row count decides whether the insert runs — but still
     * idempotent, and still inside the caller's transaction.
     */
    private void applyEmulatedUpserts(Connection targetConn, CdcTableBinding binding,
                                      List<Map<String, Object>> rows, GenericSqlDialect generic)
            throws SQLException {
        List<String> columns = binding.columnNames();
        List<String> pkColumns = binding.pkNames();
        String updateSql = generic.getUpdateByPkSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, pkColumns);
        String insertSql = ctx.getTargetDialect().getUpsertSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, List.of(),
                binding.typeByColumn(ctx.getTargetDialect(), ctx.effectiveSourceType()));

        List<String> updateBindOrder = new ArrayList<>();
        if (updateSql != null) {
            // SET clause values first, then the WHERE key values.
            for (String column : columns) {
                if (!CollectionUtil.containsIgnoreCase(pkColumns, column)) {
                    updateBindOrder.add(column);
                }
            }
            updateBindOrder.addAll(pkColumns);
        }

        Map<String, Integer> types = binding.typeByColumn(ctx.getTargetDialect(), ctx.effectiveSourceType());
        try (PreparedStatement insert = targetConn.prepareStatement(insertSql);
             PreparedStatement update = updateSql == null ? null
                     : targetConn.prepareStatement(updateSql)) {
            JdbcUtil.applyQueryTimeout(insert, queryTimeoutSeconds);
            JdbcUtil.applyQueryTimeout(update, queryTimeoutSeconds);
            for (Map<String, Object> row : rows) {
                // Each statement binds its own temporary LOBs; the row may bind twice
                // (update then insert, or update again after a unique violation).
                LobBinder.Tracker lobTracker = LobBinder.newTracker();
                try {
                    int updated = 0;
                    if (update != null) {
                        JdbcRowMapper.bind(update, row, updateBindOrder, types,
                                ctx.getTargetDialect(), lobTracker);
                        updated = update.executeUpdate();
                    }
                    if (updated > 0) {
                        continue;
                    }
                    JdbcRowMapper.bind(insert, row, columns, types, ctx.getTargetDialect(), lobTracker);
                    try {
                        insert.executeUpdate();
                    } catch (SQLException e) {
                        if (isUniqueViolation(e) && update != null) {
                            // A concurrent writer may have inserted the same key between our
                            // UPDATE and INSERT. Retrying the update converges without failing —
                            // but only if it actually reaches a row. Zero affected rows means
                            // the conflicting row carries a DIFFERENT key (another unique index,
                            // or a vendor 23000 that was not a duplicate at all); swallowing it
                            // would drop the change while the position still advances.
                            JdbcRowMapper.bind(update, row, updateBindOrder, types,
                                    ctx.getTargetDialect(), lobTracker);
                            if (update.executeUpdate() == 0) {
                                throw e;
                            }
                        } else if (isUniqueViolation(e)) {
                            // Key-only table — update is null exactly when every column is part
                            // of the key. There is no UPDATE whose row count could decide
                            // anything, so existence is checked directly: a row already holding
                            // this key is identical to the one rejected and re-delivery is a
                            // no-op, while an absent key means some other constraint rejected
                            // the row and it must surface.
                            if (!rowExists(targetConn, binding, row, pkColumns, types)) {
                                throw e;
                            }
                            log.debug("Row already present in key-only table {}; re-delivery"
                                    + " is a no-op", binding.getTargetTable());
                        } else {
                            throw e;
                        }
                    }
                } finally {
                    lobTracker.releaseAll();
                }
            }
        }
    }

    /**
     * Whether the target already holds the row with this primary key. Only reached from the
     * key-only conflict path, so the extra statement costs nothing on the normal flow.
     */
    private boolean rowExists(Connection targetConn, CdcTableBinding binding,
                              Map<String, Object> row, List<String> pkColumns,
                              Map<String, Integer> types) throws SQLException {
        GenericSqlDialect generic = (GenericSqlDialect) ctx.getTargetDialect();
        String sql = generic.getSelectByPkSql(
                ctx.getTargetSchema(), binding.getTargetTable(), pkColumns);
        try (PreparedStatement ps = targetConn.prepareStatement(sql)) {
            JdbcUtil.applyQueryTimeout(ps, queryTimeoutSeconds);
            JdbcRowMapper.bind(ps, row, pkColumns, types, ctx.getTargetDialect());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Whether the rejected INSERT may have collided with an existing key. 23505 is the
     * standard unique-violation state (DB2, H2, PostgreSQL); 23000 is what MySQL (1062),
     * SQL Server (2627/2601) and Oracle (ORA-00001) use — but the same state also carries
     * their foreign-key, CHECK and NOT NULL failures, so this is only a CANDIDATE test.
     * The callers decide by evidence: the retried UPDATE's row count, or a primary-key
     * existence probe for key-only tables. Anything that fails those checks is rethrown.
     */
    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && ("23505".equals(state) || "23000".equals(state))) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.toLowerCase().contains("duplicate")
                || message.toLowerCase().contains("unique constraint"));
    }
}
