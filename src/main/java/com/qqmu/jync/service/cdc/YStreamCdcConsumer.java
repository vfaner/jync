package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.qqmu.jync.service.cdc.YStreamEvent.Kind;
import com.qqmu.jync.service.cdc.YStreamEvent.Operation;
import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;
import com.qqmu.jync.service.cdc.YStreamServiceLifecycle.Outcome;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Long-lived YashanDB YStream CDC pipeline (v1.9.0).
 *
 * <p>Shape mirrors {@link LogMinerCdcConsumer} / {@link PgLogicalCdcConsumer}: baseline gate →
 * endless drain loop with lease renewal at the top, {@code requestStop()} as the quiet exit,
 * fatal-stop on non-transient verdicts, catch-all quiet-vs-park distinction, lease release
 * and an INFO stop line in the {@code finally} block.
 *
 * <p>What YStream changes:
 * <ul>
 *   <li>the stream carries its own transaction boundaries ({@code YSTREAM_XACT} BEGIN /
 *       COMMIT / SAVEPOINT / ROLLBACK_TO_SAVEPOINT), so the buffer is a fold plus a savepoint
 *       stack ({@link YStreamTransactionAssembler}) — no SCN-change batching heuristic;</li>
 *   <li>the position is a five-tuple packed into {@code cdc_position.lsn}
 *       ({@link YStreamPositionCodec}); Jync is the authority, never the server's periodic
 *       checkpoint;</li>
 *   <li>the service lifecycle is decided once per run ({@link YStreamServiceLifecycle}); a
 *       reconnect only re-opens the client at the last committed position;</li>
 *   <li>anything the stream cannot deliver whole (a chunked LOB row) parks the project —
 *       half a transaction applied is worse than none.</li>
 * </ul>
 *
 * <p>Position discipline (data-first): one source transaction = one target commit → lease
 * re-check → {@code advanceLsn}. A crash between commit and advanceLsn replays the
 * transaction; upserts are idempotent.
 */
@Slf4j
public final class YStreamCdcConsumer implements ManagedCdcConsumer {

    /** Consecutive failed APPLY cycles before the project parks (mirrors the other engines). */
    static final int MAX_APPLY_FAILURES = 5;

    /**
     * Extra idle pause. Zero by default: the client's own {@code next()} blocks for
     * {@code POLL_TIMEOUT_MS} and returns null on an empty poll, which already paces the
     * loop. A project-level {@code cdcIdlePollMs} override is honored as an extra pause.
     */
    private static final long ENGINE_IDLE_POLL_MS = 0L;

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
    private final YStreamClientFactory clientFactory;
    private final YStreamServiceLifecycle lifecycle;
    private final YStreamServiceOpsFactory opsFactory;

    private final CdcEventApplier applier;
    private final YStreamTransactionAssembler tx = new YStreamTransactionAssembler();
    private final CdcTransactionBudget budget;
    private final BindingSupplier bindings = new BindingSupplier();

    private volatile boolean running = true;
    /** The open stream; {@code requestStop()} closes it so a blocking poll returns at once. */
    private volatile YStreamClientAdapter currentAdapter;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger applyFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();
    /** Whether THIS cycle failed while writing the target (only those count towards the cap). */
    private boolean applyFailedThisCycle;

    private CdcReconnectBackoff backoff;
    /** Where the next client opens; the last committed position (or the baseline anchor). */
    private YStreamPosition attachFrom;
    /** Last position seen on any LCR — a COMMIT without one still checkpoints here. */
    private YStreamPosition lastSeen;

    public YStreamCdcConsumer(Project project, SyncLockService.LockHandle lock,
                              SyncContext ctx, CdcPositionStore positionStore,
                              CdcBaselineService baselineService, SyncTaskStore taskStore,
                              SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                              CdcRuntimeStats stats, SyncProperties properties,
                              YStreamClientFactory clientFactory,
                              YStreamServiceLifecycle lifecycle) {
        this(project, lock, ctx, positionStore, baselineService, taskStore, stateWriter,
                dataSourceManager, stats, properties, clientFactory, lifecycle,
                JdbcYStreamServiceOps::new);
    }

    YStreamCdcConsumer(Project project, SyncLockService.LockHandle lock, SyncContext ctx,
                       CdcPositionStore positionStore, CdcBaselineService baselineService,
                       SyncTaskStore taskStore, SyncStateWriter stateWriter,
                       DataSourceManager dataSourceManager, CdcRuntimeStats stats,
                       SyncProperties properties, YStreamClientFactory clientFactory,
                       YStreamServiceLifecycle lifecycle,
                       YStreamServiceOpsFactory opsFactory) {
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
        this.clientFactory = clientFactory;
        this.lifecycle = lifecycle;
        this.opsFactory = opsFactory;
        this.applier = new CdcEventApplier(ctx, stats, properties.getQueryTimeoutSeconds());
        this.budget = CdcTransactionBudget.from(properties);
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        // Pair the stop line with a start line so restarts do not read as a crash loop.
        try {
            stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                    ChangeType.INFO, "YStream consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the YStream start log for project {}: {}",
                    projectId, e.getMessage());
        }
        try {
            Outcome outcome = prepare(projectId);
            if (outcome.baselineRequired()) {
                // The service was started BEFORE the baseline, so every change the baseline
                // is about to read is already inside the stream; replaying from the anchor
                // afterwards is an idempotent overlap, not a gap.
                baselineService.runBaseline(ctx, lock::renew);
            }
            attachFrom = outcome.attachFrom();
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            long idlePollMs = CdcReconnectBackoff.effectiveIdlePollMs(
                    ctx.getConfig().getCdcIdlePollMs(), ENGINE_IDLE_POLL_MS);
            stats.markStarted();
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!lock.renew()) {
                    log.warn("YStream consumer for project {} stopping: lease lost (another"
                            + " node may have taken over)", projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "YStream consumer stopped: lease lost", true, 0L);
                    break;
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);
                applyFailedThisCycle = false;
                int applied;
                YStreamClientAdapter adapter = null;
                try {
                    adapter = clientFactory.open(ctx.getSourceConfig(),
                            outcome.serviceName(), attachFrom);
                    currentAdapter = adapter;
                    stats.setConnected(true);
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    applied = drain(adapter);
                } catch (Throwable e) {
                    if (applyFailedThisCycle) {
                        applyFailures.incrementAndGet();
                    }
                    lastFailure.compareAndSet(null, e);
                    applied = 0;
                    stats.setConnected(false);
                } finally {
                    if (adapter != null) {
                        adapter.close();
                    }
                    currentAdapter = null;
                }
                if (!running) {
                    break;
                }
                Throwable failure = lastFailure.get();
                if (failure == null) {
                    // A cycle that reached its end without an exception proves the
                    // open/read/apply path is healthy, so the old tally must go.
                    applyFailures.set(0);
                }
                // Whatever this cycle committed is in the store; the next open resumes
                // there even when the cycle died on a later transaction.
                attachFrom = refreshedAnchor(projectId, attachFrom);
                if (failure instanceof LockLostException) {
                    log.info("YStream consumer for project {} stopping: lease lost during a"
                            + " cycle ({})", projectId, failure.toString());
                    break;
                }
                CdcFailureClassifier.Kind kind = failure == null
                        ? CdcFailureClassifier.Kind.TRANSIENT
                        : CdcFailureClassifier.classify(failure);
                if (kind != CdcFailureClassifier.Kind.TRANSIENT || applyStrikeOut()) {
                    fatalStop(projectId, failure, kind);
                    break;
                }
                // R3b mirror: reset the backoff only when the stream demonstrably lived.
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (dwellMs >= CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS) {
                    backoff.reset();
                }
                long delay = CdcReconnectBackoff.nextCycleDelayMs(
                        failure, applied > 0, backoff, idlePollMs);
                if (failure == null) {
                    stats.setLastError(null);
                    log.debug("YStream cycle for project {} drained; next cycle in {} ms",
                            projectId, delay);
                } else {
                    String detail = CdcFailureClassifier.describe(failure);
                    stats.setLastError(detail);
                    log.warn("YStream cycle for project {} failed ({}); next cycle in {} ms",
                            projectId, detail, delay);
                }
                if (!CdcWaits.sleepWhileRunning(() -> running, delay)) {
                    break;
                }
            }
        } catch (RuntimeException | Error | SQLException e) {
            if (e instanceof LockLostException) {
                // The baseline lost the lease to another node; that node owns the position
                // row and will redo the baseline. Quiet exit, never ERROR.
                log.info("YStream consumer for project {} stopped: lease lost during the"
                        + " baseline ({}); not parking it in ERROR", projectId, e.toString());
            } else if (!running || Thread.currentThread().isInterrupted()) {
                log.info("YStream consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                // Same verdict label the loop-time fatal path uses, so a startup gate
                // (missing supplemental logging, spent service budget) reads identically
                // in the panel instead of as a bare message.
                CdcFailureClassifier.Kind kind = CdcFailureClassifier.classify(e);
                String detail = CdcFailureClassifier.describe(e);
                String labelled = kind == CdcFailureClassifier.Kind.TRANSIENT
                        ? detail : kind.name() + ": " + detail;
                stats.setLastError(labelled);
                taskStore.recordCdcFailure(projectId, labelled);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "YStream consumer failed: " + labelled, false, 0L);
                log.error("YStream consumer for project {} failed", projectId, e);
            }
        } finally {
            YStreamClientAdapter adapter = currentAdapter;
            if (adapter != null) {
                adapter.close();
            }
            currentAdapter = null;
            lock.close();
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "YStream consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the YStream stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    /**
     * The startup gate: refuse a source whose selected tables lack table-level supplemental
     * logging (their DML silently never enters the stream), then leave the service started
     * and the position row holding the anchor.
     */
    private Outcome prepare(long projectId) throws SQLException {
        List<String> tables = selectedTables();
        try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
            List<String> missing = missingSupplementalLogging(conn, tables);
            if (!missing.isEmpty()) {
                throw YStreamFatalException.blocked("table-level supplemental logging is"
                        + " missing for " + summarize(missing) + "; their changes never enter"
                        + " the YStream stream. Have a DBA run:\n" + alterScript(missing));
            }
            return lifecycle.ensure(projectId, tables, opsFactory.create(conn));
        }
    }

    /** {@code SCHEMA.TABLE} for every selected table, in configuration order. */
    private List<String> selectedTables() {
        String schema = ctx.getSourceSchema();
        List<String> qualified = new ArrayList<>();
        for (String table : ctx.getConfig().getTables()) {
            if (table == null || table.isBlank()) {
                continue;
            }
            String trimmed = table.trim();
            if (trimmed.indexOf('.') >= 0) {
                qualified.add(trimmed);
            } else if (schema == null || schema.isBlank()) {
                throw new IllegalArgumentException("YStream CDC requires the source schema to"
                        + " qualify table '" + trimmed + "'");
            } else {
                qualified.add(schema.trim() + "." + trimmed);
            }
        }
        return qualified;
    }

    /**
     * The selected tables that carry no {@code (ALL) COLUMNS} supplemental log group, in
     * upper case. Read-only: Jync never adds the group itself, it reports the ALTER script.
     */
    static List<String> missingSupplementalLogging(Connection conn, List<String> qualifiedTables)
            throws SQLException {
        Set<String> logged = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT OWNER, TABLE_NAME FROM ALL_LOG_GROUPS WHERE LOG_GROUP_TYPE='ALL'")) {
            while (rs.next()) {
                String owner = rs.getString(1);
                String table = rs.getString(2);
                if (owner != null && table != null) {
                    logged.add((owner + "." + table).toUpperCase(Locale.ROOT));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String qualified : qualifiedTables) {
            if (!logged.contains(qualified.toUpperCase(Locale.ROOT))) {
                missing.add(qualified);
            }
        }
        return missing;
    }

    private static String summarize(List<String> tables) {
        int listed = Math.min(5, tables.size());
        String names = String.join(", ", tables.subList(0, listed));
        return tables.size() > listed ? names + " and " + (tables.size() - listed) + " more"
                : names;
    }

    private static String alterScript(List<String> tables) {
        StringBuilder script = new StringBuilder();
        for (String qualified : tables) {
            if (script.length() > 0) {
                script.append('\n');
            }
            script.append("ALTER TABLE ").append(YStreamServiceAdmin.quoteQualified(qualified))
                    .append(" ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;");
        }
        return script.toString();
    }

    /** Drains the open stream until a poll timeout, a stop request or a failure. */
    private int drain(YStreamClientAdapter adapter) throws Exception {
        int applied = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            YStreamEvent event = adapter.next();
            if (event == null) {
                break;   // poll timeout: an idle source, not an error
            }
            applied += handle(event);
        }
        return applied;
    }

    /**
     * Handles one LCR. Package-private so the transaction contract is testable without a
     * source: every branch here maps one stream shape to "nothing", "buffered" or "applied".
     *
     * @return net rows committed to the target by this event (non-zero only for COMMIT)
     */
    int handle(YStreamEvent event) throws SQLException {
        if (event == null) {
            return 0;
        }
        if (event.position() != null) {
            lastSeen = event.position();
        }
        Kind kind = event.kind();
        if (kind == null) {
            return 0;
        }
        switch (kind) {
            case XACT_BEGIN:
                if (tx.begin()) {
                    // The source rolled its whole transaction back without saying so (the
                    // rollback LCR itself is delivered as BEGIN of the next transaction).
                    log.warn("Project {}: a YStream transaction ended without COMMIT; its"
                            + " buffered rows are dropped and will not be applied",
                            project.getId());
                }
                budget.reset();
                return 0;
            case XACT_COMMIT:
                return flushTransaction(event);
            case XACT_SAVEPOINT:
                // The snapshot duplicates everything folded so far; charge those copies to the
                // transaction budget so a savepoint-per-row transaction parks with a clear
                // verdict instead of growing the heap until it OOMs and replays forever.
                budget.addSavepointSnapshot(tx.savepoint());
                return 0;
            case XACT_ROLLBACK_TO_SAVEPOINT:
                tx.rollbackToSavepoint();
                return 0;
            case DDL:
                onDdl(event);
                return 0;
            case CHUNK:
                throw YStreamFatalException.blocked("a chunked LOB row of "
                        + qualifier(event) + " cannot be assembled; the transaction was not"
                        + " applied. Exclude the table from the project or switch it to"
                        + " polling capture");
            case DML:
                onDml(event);
                return 0;
            case IGNORED:
            default:
                return 0;
        }
    }

    private static String qualifier(YStreamEvent event) {
        String schema = event.schema();
        String table = event.table();
        return schema == null ? String.valueOf(table) : schema + "." + table;
    }

    private void onDml(YStreamEvent event) {
        if (event.chunked()) {
            throw YStreamFatalException.blocked("a row of " + qualifier(event)
                    + " carries chunked LOB data that cannot be assembled; the transaction"
                    + " was not applied. Exclude the table from the project or switch it to"
                    + " polling capture");
        }
        if (!matchesProjectTable(event.schema(), event.table())) {
            return;
        }
        CdcTableBinding binding = bindings.binding(event.schema(), event.table());
        if (binding == null) {
            return;   // untracked / no primary key: already counted and warned
        }
        Map<String, Object> after = event.newValues();
        Map<String, Object> before = event.oldValues();
        Operation operation = event.operation();
        if (operation == null) {
            return;
        }
        switch (operation) {
            case INSERT:
                buffer(binding, after, operation);
                return;
            case UPDATE:
                if (after == null || !hasKey(after, binding)) {
                    skipWithoutKey(binding, operation);
                    return;
                }
                // A key move is a delete of the old key plus an upsert of the new image;
                // an upsert alone would leave the old row behind forever.
                if (before != null && hasKey(before, binding)
                        && CdcPrimaryKey.changed(before, after, binding)) {
                    budget.add(before);
                    tx.delete(binding, before);
                }
                buffer(binding, after, operation);
                return;
            case DELETE:
                if (before == null || !hasKey(before, binding)) {
                    skipWithoutKey(binding, operation);
                    return;
                }
                budget.add(before);
                tx.delete(binding, before);
                return;
            default:
        }
    }

    private void buffer(CdcTableBinding binding, Map<String, Object> row,
                        Operation operation) {
        if (row == null || !hasKey(row, binding)) {
            skipWithoutKey(binding, operation);
            return;
        }
        budget.add(row);
        tx.upsert(binding, row);
    }

    /** Whether the image carries every primary-key column — without it the row has no identity. */
    private static boolean hasKey(Map<String, Object> row, CdcTableBinding binding) {
        for (String pk : binding.pkNames()) {
            if (row.get(pk) == null) {
                return false;
            }
        }
        return true;
    }

    private void skipWithoutKey(CdcTableBinding binding, Operation operation) {
        stats.addUnmappable(1);
        log.warn("YStream {} row of {}.{} carries no primary-key image; skipping it — check"
                + " the table's (ALL) COLUMNS supplemental logging",
                operation, binding.getDatabase(), binding.getTable());
    }

    private void onDdl(YStreamEvent event) {
        stats.addSkippedDdl(1);
        log.warn("YStream DDL on {} skipped (Jync does not apply source DDL); cached columns"
                + " of that table are dropped and re-read on its next row", qualifier(event));
        bindings.invalidate(event.schema(), event.table());
    }

    /**
     * Commits the buffered transaction on the target, re-checks the lease, then advances the
     * stored position — data first, position second, so a crash in between replays the same
     * transaction against idempotent upserts instead of losing it.
     */
    private int flushTransaction(YStreamEvent event) throws SQLException {
        CdcTransactionBuffer committed = tx.take();
        YStreamPosition position = event.position() != null ? event.position() : lastSeen;
        int rows = committed.size();
        if (!committed.isEmpty()) {
            Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig());
            boolean originalAutoCommit = true;
            try {
                originalAutoCommit = target.getAutoCommit();
                target.setAutoCommit(false);
                for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e
                        : committed.upserts().entrySet()) {
                    applier.applyUpserts(target, e.getKey(), e.getValue());
                }
                for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e
                        : committed.deletes().entrySet()) {
                    applier.applyDeletes(target, e.getKey(), e.getValue());
                }
                target.commit();
            } catch (RuntimeException | SQLException e) {
                applyFailedThisCycle = true;   // only target write failures feed the cap
                try {
                    target.rollback();
                } catch (SQLException ignored) {
                    // the commit failed; the rollback is a courtesy
                }
                throw e;
            } finally {
                try {
                    if (originalAutoCommit) {
                        target.setAutoCommit(true);
                    }
                } catch (SQLException ignored) {
                    // returning the connection to the pool is what matters
                }
                try {
                    target.close();
                } catch (SQLException ignored) {
                    // ditto
                }
            }
        }
        ctx.requireLease();
        if (position != null) {
            String packed = YStreamPositionCodec.encode(position);
            positionStore.advanceLsn(project.getId(), packed);
            stats.recordPositionLsn(packed);
            attachFrom = position;
        } else {
            log.warn("Project {}: a YStream COMMIT carried no position; the next open replays"
                    + " from {}", project.getId(),
                    attachFrom == null ? "the baseline anchor" : attachFrom.text());
        }
        budget.reset();
        // A transaction genuinely landed: earlier failed cycles are old news.
        applyFailures.set(0);
        return rows;
    }

    /**
     * The position to resume from after a cycle: the stored one when it moved past the local
     * anchor, the anchor otherwise. Never rewinds — a store that lags (a node that lost the
     * lease, an older row) must not drag the stream backwards.
     */
    private YStreamPosition refreshedAnchor(long projectId, YStreamPosition anchor) {
        CdcPosition row;
        try {
            row = positionStore.find(projectId).orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not re-read the YStream position of project {} after a cycle: {}",
                    projectId, e.getMessage());
            return anchor;
        }
        if (row == null || row.getLsn() == null || row.getLsn().isBlank()) {
            return anchor;
        }
        try {
            YStreamPosition stored = YStreamPositionCodec.decode(row.getLsn());
            if (anchor != null && CdcPositionStore.compareLsn(row.getLsn(),
                    YStreamPositionCodec.encode(anchor)) < 0) {
                return anchor;
            }
            return stored;
        } catch (IllegalArgumentException e) {
            return anchor;
        }
    }

    private boolean matchesProjectTable(String schema, String table) {
        if (table == null) {
            return false;
        }
        // Schema gate: two schemas with same-named tables must not both flow into CDC.
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank() && schema != null
                && !schema.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    private boolean applyStrikeOut() {
        return applyFailures.get() > MAX_APPLY_FAILURES;
    }

    private void fatalStop(long projectId, Throwable failure,
                           CdcFailureClassifier.Kind kind) {
        String label = kind != CdcFailureClassifier.Kind.TRANSIENT
                ? kind.name() : "APPLY_STRIKE_OUT";
        String detail = label + ": " + (failure == null
                ? "repeated YStream failures"
                : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("YStream consumer for project {} parking in ERROR — {}", projectId, detail);
    }

    @Override
    public void requestStop() {
        running = false;
        YStreamClientAdapter adapter = currentAdapter;
        if (adapter != null) {
            // next() blocks up to its poll timeout; closing the client is what makes a
            // stop take effect within one poll instead of at the next commit. The buffered
            // transaction replays from the stored position on the next start.
            try {
                adapter.close();
            } catch (RuntimeException e) {
                log.debug("Closing the YStream client on stop threw; ignoring", e);
            }
        }
    }

    /**
     * Per-table binding cache (mirrors {@code LogMinerCdcConsumer.BindingSupplier}). Yashan
     * keeps the dictionary spelling, so metadata is read with the upper-case name the
     * generic reader's exact-name filter expects.
     */
    private final class BindingSupplier {

        /** Marker for "this table cannot be applied" — cached so a hot table warns once. */
        private final Object unmappable = new Object();
        private final Map<String, Object> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(String schema, String table) {
            if (schema == null || table == null) {
                return null;
            }
            String key = (schema + "." + table).toUpperCase(Locale.ROOT);
            Object cached = cache.get(key);
            if (cached != null) {
                return cached == unmappable ? null : (CdcTableBinding) cached;
            }
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, schema.toUpperCase(Locale.ROOT),
                        table.toUpperCase(Locale.ROOT));
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("YStream cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", schema, table);
                    cache.put(key, unmappable);
                    return null;
                }
                CdcTableBinding binding = CdcTableBinding.of(schema.toUpperCase(Locale.ROOT),
                        table.toUpperCase(Locale.ROOT), meta,
                        ctx.getConfig().targetTableName(meta.getName()),
                        Collections.emptyMap());
                cache.put(key, binding);
                return binding;
            } catch (SQLException e) {
                throw new IllegalStateException("YStream metadata read failed for "
                        + schema + "." + table, e);
            }
        }

        /** Drop the cached binding so the next DML on this table re-reads source columns. */
        void invalidate(String schema, String table) {
            if (schema == null || table == null) {
                cache.clear();
                return;
            }
            String key = (schema + "." + table).toUpperCase(Locale.ROOT);
            if (cache.remove(key) != null) {
                log.info("Invalidated YStream binding for {}.{} after DDL event", schema, table);
            }
        }
    }
}
