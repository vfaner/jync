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
import com.qqmu.jync.service.sync.EmulatedUpsert;
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

    /**
     * Statements, bind types and column rules this run reuses across transactions; the
     * factory hands over one per run so a burst of transactions builds each statement once.
     */
    private final CdcWritePlanCache plans;
    /** False for fan-out targets: the shared capture stats count a transaction once. */
    private final boolean countTableMetrics;

    /** No-timeout convenience overload; existing call sites keep their behavior. */
    public CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats) {
        this(ctx, stats, 0);
    }

    public CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats, int queryTimeoutSeconds) {
        this(ctx, stats, queryTimeoutSeconds, new CdcWritePlanCache(ctx));
    }

    CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats, int queryTimeoutSeconds,
                    CdcWritePlanCache plans) {
        this(ctx, stats, queryTimeoutSeconds, plans, true);
    }

    CdcEventApplier(SyncContext ctx, CdcRuntimeStats stats, int queryTimeoutSeconds,
                    CdcWritePlanCache plans, boolean countTableMetrics) {
        this.ctx = ctx;
        this.stats = stats;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.plans = plans;
        this.countTableMetrics = countTableMetrics;
    }

    public void applyUpserts(Connection targetConn, CdcTableBinding binding,
                             List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        cleanse(binding, rows);
        List<String> columns = projectColumns(targetConn, binding);
        CdcWritePlanCache.UpsertPlan plan = plans.upsertPlan(binding, columns);
        try {
            write(targetConn, plan, rows);
        } catch (SQLException e) {
            // A dropped target table is not a transient disconnect: reconnecting replays the
            // same transaction against the same absent table forever. Name it so the
            // consumer can recreate that one table and replay from the same position.
            if (isMissingTable(e)) {
                throw targetTableMissing(binding, e);
            }
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
        if (countTableMetrics) {
            // Per-table accumulation also feeds the global rows-applied total.
            stats.addTableUpserts(binding.getTable(), rows.size());
        }
    }

    /** The actual write, shared by the first attempt and the projected replay. */
    private void write(Connection targetConn, CdcWritePlanCache.UpsertPlan plan,
                       List<Map<String, Object>> rows) throws SQLException {
        // Same dispatch as the polling engine: emulate only when there is a key to update
        // by; a keyless binding (unreachable in CDC — the handler gates on isApplicable)
        // degrades to the plain-INSERT branch rather than an update with an empty WHERE.
        if (plan.emulated) {
            applyEmulatedUpserts(targetConn, plan, rows);
        } else {
            applyNativeUpserts(targetConn, plan, rows);
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
                current = current.getNextException() == current
                        ? null : current.getNextException()) {
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
     * Whether the database rejected the write because the target table does not exist.
     *
     * <p>Same detection model as the ETL loader's table probe ({@code EtlSqlSupport}), so a
     * dropped table is recognised on every target family the loader can create one for:
     * SQLSTATE first (Oracle and DM keep 42000 for everything and distinguish by vendor
     * code), then the vendor codes, then the wording the analytic engines and HANA use.
     * Only a genuinely absent table may be handed to the repair path — a permission error,
     * a lock timeout or a missing database (MySQL 1049, SQL Server 4060) stays a hard
     * failure, because creating a table there would either be rejected or create it in the
     * wrong place.
     */
    static boolean isMissingTable(SQLException failure) {
        // One detection model for the whole product: the ETL loader's probe knows every
        // family the repair path can create a table for, TDengine's vendor code included.
        return com.qqmu.jync.service.etl.EtlSqlSupport.isMissingTable(failure);
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
        // The connection id is part of the key: every fan-out target has its own
        // SyncContext (same schema/type) but a different target table layout, and one
        // target's missing column must never be projected away on a healthy one.
        String cacheKey = (ctx.getTargetConfig() == null ? ""
                        : String.valueOf(ctx.getTargetConfig().getId()))
                + "|" + ctx.getTargetSchema() + "|" + binding.getTargetTable()
                + "|" + ctx.getTargetType();
        Map<String, String> targetNames = stats.cachedTargetColumns(cacheKey);
        if (targetNames == null) {
            try {
                targetNames = TargetTableVerifier.columnNamesByUpperName(targetConn,
                        ctx.getTargetSchema(), binding.getTargetTable(), ctx.getTargetType(),
                        ctx.targetPreservesIdentifierCase());
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
        CdcWritePlanCache.DeletePlan plan = plans.deletePlan(binding);
        try (PreparedStatement ps = targetConn.prepareStatement(plan.sql)) {
            JdbcUtil.applyQueryTimeout(ps, queryTimeoutSeconds);
            int inBatch = 0;
            for (Map<String, Object> row : rows) {
                JdbcRowMapper.bind(ps, row, plan.pkColumns, plan.types, ctx.getTargetDialect());
                ps.addBatch();
                if (++inBatch >= ctx.getBatchSize()) {
                    ps.executeBatch();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                ps.executeBatch();
            }
        } catch (SQLException e) {
            // Delete-only traffic must reach the same verdict as an upsert; otherwise a
            // dropped table stays invisible until an insert happens to arrive.
            if (isMissingTable(e)) {
                throw targetTableMissing(binding, e);
            }
            throw e;
        }
        if (countTableMetrics) {
            stats.addTableDeletes(binding.getTable(), rows.size());
        }
    }

    /** Names the absent target table for the classifier and the targeted repair. */
    private CdcTargetTableMissingException targetTableMissing(CdcTableBinding binding,
                                                              SQLException cause) {
        return new CdcTargetTableMissingException(binding.getTable(), binding.getTargetTable(),
                ctx.getTargetSchema(), cause);
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
        ColumnCleanser cleanser = plans.cleanser(binding.getTable());
        for (Map<String, Object> row : rows) {
            cleanser.apply(row);
        }
    }

    /** The normal path: one idempotent statement per row, executed in JDBC batches. */
    private void applyNativeUpserts(Connection targetConn, CdcWritePlanCache.UpsertPlan plan,
                                    List<Map<String, Object>> rows) throws SQLException {
        // Temporary LOBs live until the batch is executed; they are freed right after.
        LobBinder.Tracker lobTracker = LobBinder.newTracker();
        try (PreparedStatement ps = targetConn.prepareStatement(plan.sql)) {
            JdbcUtil.applyQueryTimeout(ps, queryTimeoutSeconds);
            int inBatch = 0;
            for (Map<String, Object> row : rows) {
                JdbcRowMapper.bind(ps, row, plan.bindOrder, plan.types,
                        ctx.getTargetDialect(), lobTracker);
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
    private void applyEmulatedUpserts(Connection targetConn, CdcWritePlanCache.UpsertPlan plan,
                                      List<Map<String, Object>> rows) throws SQLException {
        GenericSqlDialect generic = (GenericSqlDialect) ctx.getTargetDialect();
        String existsSql = plan.pkColumns.isEmpty() ? null
                : generic.getSelectByPkSql(
                        ctx.getTargetSchema(), plan.targetTable, plan.pkColumns);
        try (PreparedStatement insert = targetConn.prepareStatement(plan.sql);
             PreparedStatement update = plan.updateSql == null ? null
                     : targetConn.prepareStatement(plan.updateSql);
             PreparedStatement exists = targetConn.prepareStatement(existsSql)) {
            JdbcUtil.applyQueryTimeout(insert, queryTimeoutSeconds);
            JdbcUtil.applyQueryTimeout(update, queryTimeoutSeconds);
            JdbcUtil.applyQueryTimeout(exists, queryTimeoutSeconds);
            for (Map<String, Object> row : rows) {
                // Each statement binds its own temporary LOBs; the row may bind twice
                // (update then insert, or update again after a unique violation).
                LobBinder.Tracker lobTracker = LobBinder.newTracker();
                try {
                    EmulatedUpsert.Outcome outcome = EmulatedUpsert.apply(insert, update, exists,
                            false, true, (statement, kind) -> {
                                switch (kind) {
                                    case EXISTS -> JdbcRowMapper.bind(statement, row,
                                            plan.pkColumns, plan.types, ctx.getTargetDialect());
                                    case UPDATE -> JdbcRowMapper.bind(statement, row,
                                            plan.updateBindOrder, plan.types,
                                            ctx.getTargetDialect(), lobTracker);
                                    case INSERT -> JdbcRowMapper.bind(statement, row,
                                            plan.columns, plan.types,
                                            ctx.getTargetDialect(), lobTracker);
                                }
                            });
                    if (outcome == EmulatedUpsert.Outcome.NOOP) {
                        log.debug("Row already present in {}; re-delivery is a no-op",
                                plan.targetTable);
                    }
                } finally {
                    lobTracker.releaseAll();
                }
            }
        }
    }
}
