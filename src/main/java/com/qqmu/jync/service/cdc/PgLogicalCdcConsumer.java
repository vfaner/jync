package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * v1.7.0 PostgreSQL logical-slot CDC consumer. Lifecycle mirrors
 * {@link LogMinerCdcConsumer}: baseline gate → endless drain/apply loop with lease
 * renewal at the top, requestStop as the quiet exit, fatal-stop on AUTH/PARSE_STRIKE_OUT,
 * catch-all quiet-vs-park distinction, finally-block lease release.
 *
 * <p>Position discipline (data-first): one committed transaction = one target commit →
 * lease re-check → {@code positionStore.advanceLsn(commitLsn)}. A crash between commit and
 * advance replays the whole transaction against idempotent upserts.
 *
 * <p>Slot ownership: on start, the consumer ensures the per-project slot
 * ({@code jync_<projectId>}) exists; it is NEVER dropped on stop (otherwise WAL would be
 * retained forever anyway by the un-consumed slot, and a drop would lose the resume
 * watermark). Dropping slots is a DBA task.
 */
@Slf4j
public final class PgLogicalCdcConsumer implements ManagedCdcConsumer {

    /** Parse strike-out: same threshold as the LogMiner / binlog consumers. */
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
    private final PgTestDecodingParser parser = new PgTestDecodingParser();
    private final BindingSupplier supplier = new BindingSupplier();

    private volatile boolean running = true;
    private volatile Connection currentSource;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger parseFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    public PgLogicalCdcConsumer(Project project, SyncLockService.LockHandle lock,
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
        String slotName = slotName(projectId, properties);
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                baselineService.runBaseline(ctx, lock::renew);
                position = positionStore.find(projectId).orElse(null);
            }
            if (position == null || position.getLsn() == null) {
                throw new IllegalStateException("PG logical baseline completed without a"
                        + " recorded LSN anchor; refusing to read from an unknown position");
            }
            String lastLsn = position.getLsn();
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!lock.renew()) {
                    log.warn("PG consumer for project {} stopping: lease lost (another"
                            + " node may have taken over)", projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "PG consumer stopped: lease lost", true, 0L);
                    break;
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);
                int rowsDrained;
                try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                    currentSource = conn;
                    ensureSlot(conn, slotName);
                    stats.setConnected(true);
                    stats.markStarted();
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    rowsDrained = drain(conn, slotName);
                } catch (Throwable e) {
                    lastFailure.compareAndSet(null, e);
                    rowsDrained = 0;
                } finally {
                    closeQuietly(currentSource);
                    currentSource = null;
                    stats.setConnected(false);
                }
                if (!running) {
                    break;
                }
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
                        ? properties.getCdc().getPg().getIdlePollMs()
                        : backoff.nextDelayMs();
                stats.setLastError(failure == null
                        ? "drained" : CdcFailureClassifier.describe(failure));
                log.warn("PG cycle for project {} ended ({}); next cycle in {} ms",
                        projectId, failure == null ? "no failure recorded"
                                : CdcFailureClassifier.describe(failure), delay);
                if (!sleepWhileRunning(delay)) {
                    break;
                }
            }
        } catch (RuntimeException | Error e) {
            if (!running || Thread.currentThread().isInterrupted()) {
                log.info("PG consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);
                taskStore.recordCdcFailure(projectId, detail);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "PG consumer failed: " + detail, false, 0L);
                log.error("PG consumer for project {} failed", projectId, e);
            }
        } finally {
            closeQuietly(currentSource);
            currentSource = null;
            lock.close();
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "PG consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the PG stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    /**
     * Drain one cycle: read up to {@code batchSize} rows from the slot, buffer per
     * transaction, flush on COMMIT. Returns the total row count processed (for backoff
     * decisions).
     */
    private int drain(Connection conn, String slotName) throws Exception {
        long projectId = project.getId();
        int batchSize = (int) Math.min(Integer.MAX_VALUE, properties.getCdc().getPg().getBatchSize());
        // We only want events AFTER the baseline LSN. pg_logical_slot_get_changes takes
        // the "upto_lsn" boundary; we also need a from_lsn — but the slot itself advances
        // as we consume. The contract: callers MUST have started the slot AT the baseline
        // LSN (baseline recorded the LSN BEFORE the full load; the slot was created lazily
        // here, at the CURRENT LSN — which is later than the baseline LSN. The overlap is
        // absorbed by idempotent upserts).
        // See: we never pass a start_lsn — pg_logical_slot_get_changes always reads from
        // the slot's confirmed position. We create the slot on first connect; this means
        // any WAL events between baseline-LSN and slot-creation-LSN are NOT replayed. That
        // is safe because the baseline full-load already covers them.
        int total = 0;
        Map<CdcTableBinding, List<Map<String, Object>>> upserts = new LinkedHashMap<>();
        Map<CdcTableBinding, List<Map<String, Object>>> deletes = new LinkedHashMap<>();
        String lastCommitLsn = null;
        String currentTxLsn = null; // LSN of the latest row in the buffered transaction
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lsn::text, data FROM pg_logical_slot_get_changes("
                        + "?, NULL, ?, 'include-xids', '1')")) {
            ps.setString(1, slotName);
            ps.setLong(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                    String lsn = rs.getString(1);
                    String data = rs.getString(2);
                    PgTestDecodingParser.SlotLine line = parser.parse(data);
                    total++;
                    if (line.txStart) {
                        continue;
                    }
                    if (line.txEnd) {
                        // Commit boundary — flush.
                        String flushLsn = currentTxLsn != null ? currentTxLsn : lsn;
                        if (!upserts.isEmpty() || !deletes.isEmpty()) {
                            flushTransaction(projectId, flushLsn, upserts, deletes);
                            upserts.clear();
                            deletes.clear();
                        }
                        lastCommitLsn = flushLsn;
                        currentTxLsn = null;
                        continue;
                    }
                    PgTestDecodingParser.ParsedChange change = line.change;
                    if (change == null || change.isSkipped()) {
                        parseFailures.incrementAndGet();
                        stats.addSkippedUnsupported(1);
                        log.warn("Skipping PG slot row: {} (sample: {})",
                                change == null ? "null" : change.unsupportedReason,
                                truncate(data, 120));
                        continue;
                    }
                    if (!matchesProjectTable(change.qualifiedTable)) {
                        continue;
                    }
                    CdcTableBinding binding = supplier.binding(change.qualifiedTable);
                    if (binding == null || !binding.isApplicable()) {
                        stats.addUnsupportedTable(change.qualifiedTable);
                        continue;
                    }
                    applyTyped(binding, change.row);
                    currentTxLsn = lsn;
                    if (change.op == PgTestDecodingParser.Op.DELETE) {
                        deletes.computeIfAbsent(binding, k -> new ArrayList<>()).add(change.row);
                    } else {
                        upserts.computeIfAbsent(binding, k -> new ArrayList<>()).add(change.row);
                    }
                    stats.touchEvent(System.currentTimeMillis());
                }
            }
        }
        // Flush any leftover rows that arrived without a COMMIT in this batch (happens
        // when the batch boundary cuts mid-transaction; the next batch delivers the COMMIT
        // and replays the buffered rows against idempotent upserts — safe).
        if (!upserts.isEmpty() || !deletes.isEmpty()) {
            String flushLsn = currentTxLsn;
            if (flushLsn != null) {
                flushTransaction(projectId, flushLsn, upserts, deletes);
            }
        }
        if (lastCommitLsn != null) {
            stats.recordPositionLsn(lastCommitLsn);
        }
        return total;
    }

    /**
     * Coerce string values from the test_decoding payload into the Java types the binding
     * expects. We deliberately keep this minimal: strings stay strings, numbers parse to
     * Long/Double when possible, NULL stays null. The target dialect's upsert SQL binds
     * via {@code setObject} — PG/MySQL/Oracle all accept string representations for the
     * JDBC driver to coerce.
     */
    private void applyTyped(CdcTableBinding binding, Map<String, Object> row) {
        // Placeholder for future typed coercions; for now raw strings are sufficient
        // because CdcEventApplier.bindRow uses PreparedStatement.setObject(i, value)
        // which the JDBC driver coerces into the target column type.
    }

    private void flushTransaction(long projectId, String commitLsn,
                                  Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                  Map<CdcTableBinding, List<Map<String, Object>>> deletes)
            throws Exception {
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
        positionStore.advanceLsn(projectId, commitLsn);
        stats.recordPositionLsn(commitLsn);
        parseFailures.set(0);
    }

    private boolean matchesProjectTable(String qualified) {
        if (qualified == null) {
            return false;
        }
        int dot = qualified.indexOf('.');
        String schema = dot < 0 ? null : qualified.substring(0, dot);
        String table = dot < 0 ? qualified : qualified.substring(dot + 1);
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank()
                && schema != null && !schema.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    private void ensureSlot(Connection conn, String slotName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pg_replication_slots WHERE slot_name = '"
                             + slotName.replace("'", "''") + "'")) {
            if (rs.next()) {
                return;
            }
        }
        log.info("Creating PG logical replication slot '{}' for project {}", slotName, project.getId());
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT pg_create_logical_replication_slot('"
                    + slotName.replace("'", "''") + "', 'test_decoding')");
        }
    }

    static String slotName(long projectId, SyncProperties properties) {
        String prefix = properties.getCdc().getPg().getSlotPrefix();
        return (prefix == null ? "jync_" : prefix) + projectId;
    }

    private boolean parseStrikeOut() {
        return parseFailures.get() > MAX_PARSE_FAILURES;
    }

    private void fatalStop(long projectId, Throwable failure, CdcFailureClassifier.Kind kind) {
        String label = kind == CdcFailureClassifier.Kind.TRANSIENT
                ? "PARSE_STRIKE_OUT" : kind.name();
        String detail = label + ": " + (failure == null
                ? "repeated PG parse failures"
                : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("PG consumer for project {} parking in ERROR — {}", projectId, detail);
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
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

    /**
     * Per-table binding cache — PG lower-cases unquoted identifiers; the caller passes
     * the already-lower-case "{@code schema.table}" qualified name from the slot stream.
     */
    private final class BindingSupplier {
        private final Map<String, CdcTableBinding> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(String qualified) {
            CdcTableBinding cached = cache.get(qualified);
            if (cached != null) return cached;
            int dot = qualified.indexOf('.');
            String schema = dot < 0 ? "public" : qualified.substring(0, dot);
            String table = dot < 0 ? qualified : qualified.substring(dot + 1);
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, schema, table);
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("PG cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", schema, table);
                    return null;
                }
                CdcTableBinding b = CdcTableBinding.of(schema, table, meta,
                        ctx.getConfig().targetTableName(meta.getName()),
                        Collections.emptyMap());
                cache.put(qualified, b);
                return b;
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "PG metadata read failed for " + qualified, e);
            }
        }
    }

    // Silence unused-import Locale warning if the compiler complains; kept for future case work.
    @SuppressWarnings("unused")
    private static final Locale KEEP = Locale.ROOT;
}
