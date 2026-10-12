package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * v1.7.0 SQL Server CDC consumer. Lifecycle mirrors {@link PgLogicalCdcConsumer}:
 * baseline gate → poll change tables → flush per transaction → advance LSN.
 *
 * <p>Source semantics: SQL Server CDC writes every row change into
 * {@code cdc.<schema>_<table>_CT}; each row carries {@code __$start_lsn} (the
 * commit LSN of the originating transaction), {@code __$operation} (1=delete, 2=insert,
 * 4=update-after, and optionally 3=update-before when 'all update old' is requested),
 * and the captured columns. We call
 * {@code cdc.fn_cdc_get_all_changes_<capture_instance>(@from_lsn, @to_lsn, 'all update old')}
 * per cycle with {@code @to_lsn = sys.fn_cdc_get_max_lsn()}.
 *
 * <p>Position discipline (data-first): per-commit transaction → commit target →
 * {@code requireLease} → {@code advanceLsn(maxLsnOfBatch)}.
 *
 * <p><b>LSN format invariant (v1.7.1 fix)</b>: every LSN we read, persist, or bind is
 * produced by {@code CONVERT(varchar(32), lsn, 1)} — a {@code 0x} prefixed, lowercase,
 * 22-character string (binary(10) = 20 hex chars + "0x"). Binding back uses
 * {@code CONVERT(binary(10), ?, 1)} which requires that exact shape. Previously we mixed
 * a self-built uppercase-no-prefix hex() with the SQL-produced form, which made the
 * second cycle throw "Conversion failed when converting ... to binary".
 *
 * <p><b>Interval semantics (v1.7.1 fix)</b>: {@code fn_cdc_get_all_changes_*} is
 * inclusive-inclusive. To avoid replaying the batch's last committed transaction, the
 * from_lsn we PASS to the function is {@code sys.fn_cdc_increment_lsn(@from_lsn)} while
 * the persisted watermark stays the txLsn (so a crash between commit and advance still
 * replays against idempotent upserts).
 *
 * <p><b>Failure semantics (v1.7.1 fix)</b>: if ANY capture instance's ResultSet fails
 * mid-iteration, the whole drain aborts without advancing the watermark — otherwise the
 * failed instance's rows in (fromLsn, maxLsn] would be skipped forever. The retry path
 * re-enters drain() with the same fromLsn.
 */
@Slf4j
public final class SqlServerCdcConsumer implements ManagedCdcConsumer {

    static final int MAX_PARSE_FAILURES = 5;

    private final Project project;
    private final SyncLockService.LockHandle lock;
    private final SyncContext ctx;
    private final CdcPositionStore positionStore;
    private final CdcBaselineService baselineService;
    private final SyncTaskStore taskStore;
    private final SyncStateWriter stateWriter;
    private final DataSourceManager dataSourceManager;
    private final CdcRuntimeStats stats;
    private final SyncProperties properties;

    private final CdcEventApplier applier;
    private final BindingSupplier supplier = new BindingSupplier();

    private volatile boolean running = true;
    private volatile Connection currentSource;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger parseFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    public SqlServerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                                SyncContext ctx, CdcPositionStore positionStore,
                                CdcBaselineService baselineService, SyncTaskStore taskStore,
                                SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                                CdcRuntimeStats stats, SyncProperties properties) {
        this.project = project;
        this.lock = lock;
        this.ctx = ctx;
        this.positionStore = positionStore;
        this.baselineService = baselineService;
        this.taskStore = taskStore;
        this.stateWriter = stateWriter;
        this.dataSourceManager = dataSourceManager;
        this.stats = stats;
        this.properties = properties;
        this.applier = new CdcEventApplier(ctx, stats, properties.getQueryTimeoutSeconds());
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                baselineService.runBaseline(ctx, lock::renew);
                position = positionStore.find(projectId).orElse(null);
            }
            if (position == null || position.getLsn() == null) {
                throw new IllegalStateException("SQL Server CDC baseline completed without"
                        + " a recorded LSN anchor");
            }
            String fromLsn = position.getLsn();
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!lock.renew()) {
                    log.warn("SQLServer CDC consumer for project {} stopping: lease lost",
                            projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "SQLServer CDC stopped: lease lost", true, 0L);
                    break;
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);
                int rowsDrained;
                try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                    currentSource = conn;
                    stats.setConnected(true);
                    stats.markStarted();
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    DrainResult result = drain(conn, fromLsn);
                    rowsDrained = result.rows;
                    if (result.nextLsn != null) {
                        fromLsn = result.nextLsn;
                    }
                } catch (Throwable e) {
                    // Any drain exception leaves fromLsn unchanged: the next iteration
                    // re-reads the same interval (data-first at-least-once).
                    lastFailure.compareAndSet(null, e);
                    rowsDrained = 0;
                } finally {
                    closeQuietly(currentSource);
                    currentSource = null;
                    stats.setConnected(false);
                }
                if (!running) break;
                Throwable failure = lastFailure.get();
                CdcFailureClassifier.Kind kind = failure == null
                        ? CdcFailureClassifier.Kind.TRANSIENT
                        : CdcFailureClassifier.classify(failure);
                if (kind != CdcFailureClassifier.Kind.TRANSIENT || parseStrikeOut()) {
                    fatalStop(projectId, failure, kind);
                    break;
                }
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (dwellMs >= CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS) {
                    backoff.reset();
                }
                long delay = rowsDrained == 0
                        ? properties.getCdc().getMssql().getIdlePollMs()
                        : backoff.nextDelayMs();
                stats.setLastError(failure == null ? "drained"
                        : CdcFailureClassifier.describe(failure));
                log.info("SQLServer cycle for project {} ended ({}); next cycle in {} ms",
                        projectId, failure == null ? "no failure recorded"
                                : CdcFailureClassifier.describe(failure), delay);
                if (!sleepWhileRunning(delay)) break;
            }
        } catch (RuntimeException | Error e) {
            if (!running || Thread.currentThread().isInterrupted()) {
                log.info("SQLServer consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);
                taskStore.recordCdcFailure(projectId, detail);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "SQLServer consumer failed: " + detail, false, 0L);
                log.error("SQLServer consumer for project {} failed", projectId, e);
            }
        } finally {
            closeQuietly(currentSource);
            currentSource = null;
            lock.close();
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "SQLServer consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the SQLServer stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    private static final class DrainResult {
        int rows;
        String nextLsn;
    }

    /**
     * Read the current {@code fn_cdc_get_max_lsn()}, then pull every change between
     * (fromLsn, maxLsn] across all tracked capture instances. Rows are grouped by
     * {@code __$start_lsn} — each group is one committed transaction — and flushed.
     *
     * <p>Aborts the whole drain (throws SQLException) if ANY capture instance fails
     * mid-iteration: the watermark then stays at fromLsn and the retry re-reads the
     * entire interval — no rows are skipped silently.
     */
    private DrainResult drain(Connection conn, String fromLsn) throws Exception {
        long projectId = project.getId();
        DrainResult out = new DrainResult();
        String maxLsn = readMaxLsn(conn);
        if (maxLsn == null || maxLsn.equalsIgnoreCase(fromLsn)) {
            return out; // nothing new
        }
        List<CaptureInstance> instances = discoverCaptureInstances(conn);
        if (instances.isEmpty()) {
            stats.addSkippedUnsupported(1);
            // No capture instance means nothing will ever show up past fromLsn without
            // DBA action: do NOT advance the watermark — that would hide the gap. Advance
            // to maxLsn only when at least one tracked instance exists, so the next poll
            // starts from the current position when the DBA enables CDC mid-stream.
            return out;
        }
        // Group rows by start_lsn so each transaction flushes atomically.
        Map<String, Map<CdcTableBinding, List<Map<String, Object>>>> upsertsByTx =
                new LinkedHashMap<>();
        Map<String, Map<CdcTableBinding, List<Map<String, Object>>>> deletesByTx =
                new LinkedHashMap<>();
        String highWater = fromLsn;
        for (CaptureInstance ci : instances) {
            // from_lsn is INCLUSIVE — pass fn_cdc_increment_lsn to skip the watermark
            // transaction we already applied on the previous cycle. The persisted value
            // remains fromLsn; only the SQL interval shifts.
            String sql = "SELECT __$start_lsn, __$operation, *"
                    + " FROM cdc.fn_cdc_get_all_changes_" + ci.captureInstance
                    + "((SELECT sys.fn_cdc_increment_lsn(CONVERT(binary(10), ?, 1))),"
                    + " CONVERT(binary(10), ?, 1),'all update old')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fromLsn);
                ps.setString(2, maxLsn);
                try (ResultSet rs = ps.executeQuery()) {
                    // Cache the real column set of the CT row ONCE per ci — readRow
                    // consults this to distinguish "not captured" from genuine errors.
                    Set<String> availableCols = availableColumns(rs);
                    while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                        String startLsn = rs.getString(1); // already CONVERT(varchar, 1) shape
                        int op = rs.getInt(2);
                        CdcTableBinding binding = supplier.binding(ci);
                        if (binding == null || !binding.isApplicable()) {
                            stats.addUnsupportedTable(ci.schema + "." + ci.table);
                            continue;
                        }
                        Map<String, Object> row = readRow(rs, binding, availableCols);
                        if (op == 1) { // delete
                            deletesByTx.computeIfAbsent(startLsn, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(binding, k -> new ArrayList<>())
                                    .add(row);
                        } else if (op == 2 || op == 4) { // insert or update-after
                            upsertsByTx.computeIfAbsent(startLsn, k -> new LinkedHashMap<>())
                                    .computeIfAbsent(binding, k -> new ArrayList<>())
                                    .add(row);
                        } // op == 3 is update-before — we don't need it (upsert semantics)
                        highWater = maxLsn;
                        out.rows++;
                        stats.touchEvent(System.currentTimeMillis());
                    }
                }
            } catch (SQLException e) {
                // SQL Server error 22054 == "requested LSN interval invalid" (e.g. this
                // instance has no rows in the window). Anything else — permission,
                // network, column drift — aborts the whole drain so we retry instead of
                // silently skipping this ci.
                int sqlClass = e.getErrorCode();
                if (sqlClass == 22054) {
                    log.debug("SQLServer CDC interval empty for {}: {}",
                            ci.captureInstance, e.getMessage());
                    continue;
                }
                // Propagate — the caller leaves fromLsn untouched and retries.
                throw e;
            }
        }
        // Flush per-transaction in LSN order.
        for (Map.Entry<String, Map<CdcTableBinding, List<Map<String, Object>>>> e
                : upsertsByTx.entrySet()) {
            String txLsn = e.getKey();
            flushTransaction(projectId, txLsn, e.getValue(),
                    deletesByTx.getOrDefault(txLsn, Collections.emptyMap()));
        }
        // Leftover deletes for a start_lsn with no upserts (all-delete transaction).
        for (Map.Entry<String, Map<CdcTableBinding, List<Map<String, Object>>>> e
                : deletesByTx.entrySet()) {
            if (!upsertsByTx.containsKey(e.getKey())) {
                flushTransaction(projectId, e.getKey(), Collections.emptyMap(), e.getValue());
            }
        }
        out.nextLsn = highWater;
        return out;
    }

    private void flushTransaction(long projectId, String txLsn,
                                  Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                  Map<CdcTableBinding, List<Map<String, Object>>> deletes)
            throws Exception {
        if (upserts.isEmpty() && deletes.isEmpty()) return;
        Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig());
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : upserts.entrySet()) {
                applier.applyUpserts(target, e.getKey(), e.getValue());
            }
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : deletes.entrySet()) {
                applier.applyDeletes(target, e.getKey(), e.getValue());
            }
            target.commit();
        } catch (Exception e) {
            try { target.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try {
                if (originalAutoCommit) target.setAutoCommit(true);
            } catch (SQLException ignored) { }
            try { target.close(); } catch (SQLException ignored) { }
        }
        ctx.requireLease();
        positionStore.advanceLsn(projectId, txLsn);
        stats.recordPositionLsn(txLsn);
        parseFailures.set(0);
    }

    private String readMaxLsn(Connection conn) throws SQLException {
        // CONVERT(varchar(32), lsn, 1) yields the canonical "0x" + lowercase hex form
        // used everywhere downstream. Never mix in byte[]→hex() or the format check in
        // drain() (equalsIgnoreCase) will always fail.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * Discover every enabled capture instance on this DB and filter to the tables the
     * project actually wants. When a source table has MULTIPLE capture instances (schema
     * change → disable/enable), keep only the one with the highest start_lsn — the
     * others are historical and would double-apply the same row events.
     */
    private List<CaptureInstance> discoverCaptureInstances(Connection conn) throws SQLException {
        // ORDER BY start_lsn ASC lets the per-table loop pick the LAST (newest) one.
        String sql = "SELECT source_schema_name, source_table_name, capture_instance"
                + " FROM cdc.change_tables"
                + " ORDER BY source_schema_name, source_table_name, start_lsn";
        Map<String, CaptureInstance> byTable = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                String ci = rs.getString(3);
                if (!matchesProjectTable(schema, table)) continue;
                String key = (schema + "." + table).toLowerCase(Locale.ROOT);
                byTable.put(key, new CaptureInstance(schema, table, ci)); // last one wins
            }
        }
        return new ArrayList<>(byTable.values());
    }

    private boolean matchesProjectTable(String schema, String table) {
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank()
                && schema != null && !schema.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    /** Column names actually present in the CT ResultSet (lowercased for lookup). */
    private static Set<String> availableColumns(ResultSet rs) throws SQLException {
        Set<String> out = new HashSet<>();
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            String name = rs.getMetaData().getColumnName(i);
            if (name != null) out.add(name.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Read the captured columns into a map keyed by column name (case-preserved).
     * Columns the CT does NOT carry (enable_table @captured_column_list subset) are
     * omitted from the row map entirely — the applier skips them, so the target keeps
     * its existing value instead of being overwritten with NULL. Previously every
     * missing column was bound to NULL, which silently overwrote the target.
     *
     * <p>True SQLException (connection drop, type mismatch) propagates — the drain
     * aborts and the watermark does not advance, so the same rows re-read on retry.
     */
    private Map<String, Object> readRow(ResultSet rs, CdcTableBinding binding,
                                        Set<String> availableCols) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String col : binding.columnNames()) {
            if (!availableCols.contains(col.toLowerCase(Locale.ROOT))) {
                continue; // not captured by this CDC instance — leave absent
            }
            Object value = rs.getObject(col);
            // Unknown JDBC types (hierarchyid, geography, sqlxml from SQL Server) arrive
            // as driver-proprietary classes that the target JDBC cannot bind. Fall back
            // to getString() so the row still flows through — with a string, the target
            // driver has a known coercion path.
            if (value != null && looksLikeDriverProprietary(value.getClass().getName())) {
                row.put(col, rs.getString(col));
            } else {
                row.put(col, value);
            }
        }
        return row;
    }

    private static boolean looksLikeDriverProprietary(String className) {
        return className.startsWith("com.microsoft.sqlserver.")
                || className.startsWith("com.microsoft.sqlserver.jdbc.")
                || className.contains("hierarchyid")
                || className.contains("SqlXml");
    }

    private boolean parseStrikeOut() { return parseFailures.get() > MAX_PARSE_FAILURES; }

    private void fatalStop(long projectId, Throwable failure, CdcFailureClassifier.Kind kind) {
        String label = kind == CdcFailureClassifier.Kind.TRANSIENT
                ? "PARSE_STRIKE_OUT" : kind.name();
        String detail = label + ": " + (failure == null
                ? "repeated SQLServer CDC failures" : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("SQLServer consumer for project {} parking in ERROR — {}", projectId, detail);
    }

    private boolean sleepWhileRunning(long delayMs) {
        long remaining = delayMs;
        while (remaining > 0 && running && !Thread.currentThread().isInterrupted()) {
            long slice = Math.min(100L, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return running && !Thread.currentThread().isInterrupted();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void requestStop() {
        // Do NOT close the source connection here: ps.executeQuery() would throw
        // "Connection closed" mid-drain, which classify() might bucket as PERMANENT
        // and park the project in ERROR over a deliberate stop. Just flip the flag —
        // the next slice of sleepWhileRunning / drain's running-check exits cleanly.
        running = false;
    }

    private static final class CaptureInstance {
        final String schema;
        final String table;
        final String captureInstance;
        CaptureInstance(String schema, String table, String captureInstance) {
            this.schema = schema;
            this.table = table;
            this.captureInstance = captureInstance;
        }
    }

    /**
     * Per-table binding cache. Negative results (no PK / metadata missing) are ALSO
     * cached under a sentinel — otherwise the 1s idle poll re-opens a source connection
     * and re-reads information_schema every cycle for every un-mappable table.
     */
    private final class BindingSupplier {
        /** Marker in the cache for "this table is not mappable; don't re-read metadata". */
        private final Object notMappableSentinel = new Object();

        private final Map<String, Object> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(CaptureInstance ci) {
            String key = (ci.schema + "." + ci.table).toLowerCase(Locale.ROOT);
            Object cached = cache.get(key);
            if (cached != null) {
                return cached == notMappableSentinel ? null : (CdcTableBinding) cached;
            }
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, ci.schema, ci.table);
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("SQLServer cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", ci.schema, ci.table);
                    cache.put(key, notMappableSentinel);
                    return null;
                }
                CdcTableBinding b = CdcTableBinding.of(ci.schema, ci.table, meta,
                        ctx.getConfig().targetTableName(meta.getName()),
                        Collections.emptyMap());
                cache.put(key, b);
                return b;
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "SQLServer metadata read failed for " + key, e);
            }
        }
    }
}
