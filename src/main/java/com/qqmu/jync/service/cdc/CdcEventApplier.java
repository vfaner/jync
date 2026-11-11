package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.lob.LobBinder;
import com.qqmu.jync.service.sync.ColumnCleanser;
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.UniqueViolations;
import com.qqmu.jync.util.CollectionUtil;
import com.qqmu.jync.util.JdbcUtil;
import com.qqmu.jync.util.TargetTableVerifier;

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
        List<String> columns = projectColumns(targetConn, binding);
        try {
            write(targetConn, binding, columns, rows);
        } catch (SQLException e) {
            if (!isMissingColumn(e)) {
                throw e;
            }
            // The target lacks a column the source has, so the two have drifted: a DDL ran
            // after this consumer started, or somebody changed the target by hand. Turning
            // the projection on makes the replayed transaction drop the missing column
            // instead of failing again — the other columns still converge through the
            // idempotent upsert, and the omission stays visible on the panel.
            stats.forceProjection();
            stats.invalidateTargetColumns();
            stats.addSchemaMismatch();
            log.warn("Target {} is missing a column the source has; the transaction is replayed"
                    + " with that column projected away: {}",
                    binding.getTargetTable(), e.getMessage());
            // The sentinel prefix lets the consumer recognise this as a recoverable schema
            // mismatch: the transaction is replayed (the position does not move), and the
            // projection above makes the replay succeed.
            throw new SQLException("cdc schema mismatch: " + e.getMessage(), e);
        }
        // Per-table accumulation also feeds the global rows-applied total.
        stats.addTableUpserts(binding.getTable(), rows.size());
    }

    /** The actual write, shared by the first attempt and the projected replay. */
    private void write(Connection targetConn, CdcTableBinding binding, List<String> columns,
                       List<Map<String, Object>> rows) throws SQLException {
        // Same dispatch as the polling engine: emulate only when there is a key to update
        // by; a keyless binding (unreachable in CDC — the handler gates on isApplicable)
        // degrades to the plain-INSERT branch rather than an update with an empty WHERE.
        if (!binding.pkNames().isEmpty()
                && ctx.getTargetDialect() instanceof GenericSqlDialect
                && !((GenericSqlDialect) ctx.getTargetDialect()).supportsNativeUpsert()) {
            applyEmulatedUpserts(targetConn, binding, columns, rows,
                    (GenericSqlDialect) ctx.getTargetDialect());
        } else {
            applyNativeUpserts(targetConn, binding, columns, rows);
        }
    }

    /**
     * Whether the database rejected the write because a column does not exist.
     *
     * <p>MySQL reports 1054 / SQLSTATE 42S22, PostgreSQL 42703, SQL Server 207, Oracle 904;
     * the wording check covers the drivers that drop the vendor code. Only a genuinely
     * missing column may be projected away — anything else stays a hard failure.
     */
    static boolean isMissingColumn(SQLException failure) {
        for (SQLException current = failure; current != null;
                current = current.getNextException()) {
            String state = current.getSQLState();
            int code = current.getErrorCode();
            if ("42S22".equals(state) || "42122".equals(state)
                    || code == 1054 || code == 207 || code == 904 || code == 42703) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("unknown column")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Source columns the target actually has.
     *
     * <p>A skipped DDL leaves the source one or more columns ahead of the target. Writing the
     * source layout verbatim then fails the whole transaction with {@code Unknown column},
     * the position never advances and the stream parks in ERROR after replaying the same
     * transaction forever. Projecting away only the columns the target lacks keeps the stream
     * moving (the other columns still converge through the idempotent upsert) until an
     * operator aligns the target; the skipped DDL stays in the change log and the omission is
     * counted on the panel, so the temporary gap is never silent.
     *
     * <p>The projection is applied to the SQL column list only — never to the row maps, which
     * are replayed under at-least-once, and never to the binding, whose layout signature is
     * persisted with the position.
     */
    private List<String> projectColumns(Connection targetConn, CdcTableBinding binding) {
        List<String> columns = binding.columnNames();
        // Without a DDL in this stream the target layout equals the source one, so reading
        // the target dictionary on every transaction would be pure overhead. The projection
        // only runs when a DDL was skipped, or when a write proved the two have drifted.
        if (!stats.projectionRequired()) {
            return columns;
        }
        // Dictionary lookups need the target's catalog/schema convention. Without a target
        // type (hand-built contexts in unit tests) keep the source layout untouched.
        if (ctx.getTargetType() == null) {
            return columns;
        }
        String cacheKey = ctx.getTargetSchema() + "|" + binding.getTargetTable()
                + "|" + ctx.getTargetType();
        Map<String, String> targetNames = stats.cachedTargetColumns(cacheKey);
        if (targetNames == null) {
            try {
                targetNames = TargetTableVerifier.columnNamesByUpperName(targetConn,
                        ctx.getTargetSchema(), binding.getTargetTable(), ctx.getTargetType());
            } catch (SQLException | RuntimeException e) {
                // Metadata must never stop the stream: a target we cannot inspect writes
                // exactly as before and lets the database decide.
                log.debug("Cannot read the columns of target {}; keeping the source layout: {}",
                        binding.getTargetTable(), e.toString());
                return columns;
            }
            if (targetNames == null || targetNames.isEmpty()) {
                return columns;
            }
            // The target's shape rarely changes on its own, so the answer is kept for a
            // while instead of being read again on every transaction.
            stats.cacheTargetColumns(cacheKey, targetNames);
        }
        List<String> kept = new ArrayList<>();
        List<String> pruned = new ArrayList<>();
        for (String column : columns) {
            if (targetNames.containsKey(column.toUpperCase(Locale.ROOT))) {
                kept.add(column);
            } else {
                pruned.add(column);
            }
        }
        if (pruned.isEmpty()) {
            return columns;
        }
        // A missing primary key cannot be projected away: neither the upsert's key nor
        // delete-by-PK would identify a row. Leave the layout alone so the database rejects
        // the write, the position stays put and the transaction replays.
        for (String pk : binding.pkNames()) {
            if (CollectionUtil.containsIgnoreCase(pruned, pk)) {
                log.warn("Target {} is missing primary key column {}; not projecting columns"
                        + " — the write will fail and replay until the target is aligned",
                        binding.getTargetTable(), pk);
                return columns;
            }
        }
        // The gap is recorded under the SOURCE table: a replay reads the source and lets the
        // project's own table mapping resolve the target name.
        stats.recordPrunedGap(binding.getTable(), pruned);
        if (stats.recordPrunedColumns(pruned.size())) {
            // Name the exact schema and table: "the target" is ambiguous when source and
            // target live in different databases, and the operator has to know which one
            // to alter before the missing values can be replayed.
            log.warn("CDC writes only the columns the target has: {} are missing on target {}.{}"
                    + " and their values are NOT replicated. Align that exact schema (see the"
                    + " skipped DDL in the change log) — the next transaction then picks them up."
                    + " Columns the target has: {}",
                    pruned, targetSchemaName(), binding.getTargetTable(),
                    presentColumns(targetNames));
        }
        return kept;
    }

    /** The schema the target connection actually resolves to. */
    private String targetSchemaName() {
        String schema = ctx.getTargetSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        return ctx.getTargetConfig() == null ? "?" : ctx.getTargetConfig().getDatabaseName();
    }

    /** The target's column names, sorted and capped so the log line stays readable. */
    private static List<String> presentColumns(Map<String, String> targetNames) {
        List<String> present = new ArrayList<>(new TreeSet<>(targetNames.keySet()));
        return present.size() > 40 ? present.subList(0, 40) : present;
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
                                    List<String> columns,
                                    List<Map<String, Object>> rows) throws SQLException {
        List<String> pkColumns = binding.pkNames();
        Map<String, Integer> types = binding.typeByColumn(ctx.getTargetDialect(), ctx.effectiveSourceType());
        // Same typed-marker support as the polling write path: DB2 MERGE re-resolves
        // untyped markers across ON/UPDATE/INSERT and fails with SQL0418 when the contexts
        // disagree; other dialects ignore the extra argument.
        Map<String, String> declarations = binding.declarationByColumn(
                ctx.getTargetDialect(), ctx.effectiveSourceType());
        String sql = ctx.getTargetDialect().getUpsertSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, pkColumns, types,
                false, declarations);
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
                                      List<String> columns,
                                      List<Map<String, Object>> rows, GenericSqlDialect generic)
            throws SQLException {
        List<String> pkColumns = binding.pkNames();
        // One map for both consumers: the insert SQL's placeholder types and the per-row
        // binding below. Building it twice walked every column (and re-mapped each vendor
        // type) twice per flushed transaction.
        Map<String, Integer> types = binding.typeByColumn(
                ctx.getTargetDialect(), ctx.effectiveSourceType());
        String updateSql = generic.getUpdateByPkSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, pkColumns);
        String insertSql = ctx.getTargetDialect().getUpsertSql(
                ctx.getTargetSchema(), binding.getTargetTable(), columns, List.of(), types);

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
                        if (UniqueViolations.isUniqueViolation(e) && update != null) {
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
                        } else if (UniqueViolations.isUniqueViolation(e)) {
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
}
