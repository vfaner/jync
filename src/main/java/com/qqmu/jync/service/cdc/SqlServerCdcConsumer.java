package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                log.warn("SQLServer cycle for project {} ended ({}); next cycle in {} ms",
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
            return out;
        }
        // Group rows by start_lsn so each transaction flushes atomically.
        Map<String, Map<CdcTableBinding, List<Map<String, Object>>>> upsertsByTx =
                new LinkedHashMap<>();
        Map<String, Map<CdcTableBinding, List<Map<String, Object>>>> deletesByTx =
                new LinkedHashMap<>();
        String highWater = fromLsn;
        for (CaptureInstance ci : instances) {
            String sql = "SELECT __$start_lsn, __$operation, *"
                    + " FROM cdc.fn_cdc_get_all_changes_" + ci.captureInstance
                    + "(CONVERT(binary(10), ?, 1), CONVERT(binary(10), ?, 1),'all update old')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fromLsn);
                ps.setString(2, maxLsn);
                try (ResultSet rs = ps.executeQuery()) {
                    while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                        String startLsn = hex(rs.getBytes("__$start_lsn"));
                        int op = rs.getInt("__$operation");
                        CdcTableBinding binding = supplier.binding(ci);
                        if (binding == null || !binding.isApplicable()) {
                            stats.addUnsupportedTable(ci.schema + "." + ci.table);
                            continue;
                        }
                        Map<String, Object> row = readRow(rs, binding);
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
                // 228 == "requested LSN interval invalid"; skip this instance, continue.
                log.warn("SQLServer CDC read failed for {}: {}", ci.captureInstance,
                        e.getMessage());
                parseFailures.incrementAndGet();
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
        try (Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig())) {
            target.setAutoCommit(false);
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : upserts.entrySet()) {
                applier.applyUpserts(target, e.getKey(), e.getValue());
            }
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : deletes.entrySet()) {
                applier.applyDeletes(target, e.getKey(), e.getValue());
            }
            target.commit();
        }
        ctx.requireLease();
        positionStore.advanceLsn(projectId, txLsn);
        stats.recordPositionLsn(txLsn);
        parseFailures.set(0);
    }

    private String readMaxLsn(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * Discover every enabled capture instance on this DB and filter to the tables the
     * project actually wants. Rows not listed in the project are dropped here so the
     * consumer never wastes cycles on them.
     */
    private List<CaptureInstance> discoverCaptureInstances(Connection conn) throws SQLException {
        List<CaptureInstance> all = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_schema_name, source_table_name, capture_instance"
                        + " FROM cdc.change_tables")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                String ci = rs.getString(3);
                if (!matchesProjectTable(schema, table)) continue;
                all.add(new CaptureInstance(schema, table, ci));
            }
        }
        return all;
    }

    private boolean matchesProjectTable(String schema, String table) {
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank()
                && schema != null && !schema.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    /** Read the captured columns into a map keyed by column name (case-preserved). */
    private Map<String, Object> readRow(ResultSet rs, CdcTableBinding binding) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String col : binding.columnNames()) {
            try {
                row.put(col, rs.getObject(col));
            } catch (SQLException e) {
                // Column not captured by the CDC instance — leave it absent; applier skips it.
                row.put(col, null);
            }
        }
        return row;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
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
        running = false;
        Connection conn = currentSource;
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) { }
        }
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

    private final class BindingSupplier {
        private final Map<String, CdcTableBinding> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(CaptureInstance ci) {
            String key = (ci.schema + "." + ci.table).toLowerCase(Locale.ROOT);
            CdcTableBinding cached = cache.get(key);
            if (cached != null) return cached;
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, ci.schema, ci.table);
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("SQLServer cannot map source table {}.{} (missing or no PK)",
                            ci.schema, ci.table);
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
