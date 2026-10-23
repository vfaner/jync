package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.sync.LockLostException;
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
 * <p>Position discipline (data-first): one peeked transaction = one target commit →
 * lease re-check → {@code positionStore.advanceLsn(commitLsn)} →
 * {@code pg_replication_slot_advance(commitLsn)}. A crash anywhere after the target
 * commit replays the whole transaction against idempotent upserts; a crash before the
 * slot advance re-peeks it verbatim (F-11).
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

    /**
     * How long a table's metadata is trusted after a refresh triggered by a row-shape
     * mismatch. A binding that keeps disagreeing with the stream (a column the metadata
     * reader does not report, say) must not turn into one source connection per row; a
     * real DDL change is picked up by the first row it affects anyway.
     */
    private static final long BINDING_REFRESH_COOLDOWN_MS = 10_000L;

    /**
     * How long a "this table cannot be applied" verdict is trusted. Nothing in the slot
     * stream announces DDL (logical decoding of test_decoding emits row changes only), so a
     * table that gains a primary key while the consumer runs would otherwise stay
     * unsyncable until the process restarts or the baseline is rebuilt. Costs at most one
     * metadata read per table per interval, and only for tables that keep emitting events.
     */
    private static final long BINDING_UNMAPPABLE_RECHECK_MS = 10 * 60_000L;

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
    // F-10:连续失败周期计数(drain 抛异常的周期 +1,成功 flush 一个事务清零)。
    // 只覆盖解析失败的 parseFailures 拦不住「每周期必然失败的目标端写入」——典型是
    // 源库停机期 ADD COLUMN 而目标表没有该列:openGauss 42703 分类为 TRANSIENT,
    // 旧代码空转无限重试,既不落数据也不 park。
    private final AtomicInteger applyFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    /** Per-table aggregated change-log flusher (10s windows, final flush on stop). */
    private final CdcChangeWindowLogger windowLogger;

    /** LOB spill store for buffered rows, built on first use; null when switched off. */
    private volatile LobSpillStore lobSpillStore;
    /** Owns the current transaction's spilled values; null between transactions. */
    private CdcLobSpiller spiller;

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
        this.windowLogger = new CdcChangeWindowLogger(project.getId(), stats, stateWriter,
                taskStore, properties.getCdc().getChangeWindowMs());
    }

    @Override
    public void run() {
        long projectId = project.getId();
        taskStore.markCdcRunning(projectId);
        // Pair the stop line with a start line so restarts do not read as a crash loop.
        try {
            stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                    ChangeType.INFO, "PG consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the PG start log for project {}: {}",
                    projectId, e.getMessage());
        }
        String slotName = slotName(projectId, properties);
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                // F-8(docker 实测):slot 必须在基线之前建好。pg_create_logical_replication_slot
                // 只保留创建点之后的 WAL;旧代码首轮 drain 才懒建 slot,基线完成后、建槽前
                // 提交的事务落在 slot 起始位点之前,永久丢失。先开一个短连接建槽,再跑
                // 基线:基线与 slot 的重叠由幂等 upsert 吸收,基线快照之后的提交只在 slot 里,
                // 一条不丢。
                try (Connection slotConn =
                             dataSourceManager.getConnection(ctx.getSourceConfig())) {
                    ensureSlot(slotConn, slotName);
                }
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
            long idlePollMs = CdcReconnectBackoff.effectiveIdlePollMs(
                    ctx.getConfig().getCdcIdlePollMs(),
                    properties.getCdc().getPg().getIdlePollMs());
            windowLogger.start();
            // The throughput window covers the whole run, not one poll cycle: the panel divides
            // the cumulative rows applied by the elapsed window. Stamping it inside the loop
            // reported 0.0 right after every poll and an ever-growing rate once a cycle took
            // longer than a second (see CdcRuntimeStats.eventsPerSecond's one-second floor).
            stats.markStarted();
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
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    rowsDrained = drain(conn, slotName);
                } catch (Throwable e) {
                    lastFailure.compareAndSet(null, e);
                    rowsDrained = 0;
                    applyFailures.incrementAndGet();
                    // The poll loop opens a connection per cycle, so "connected" cannot be tied
                    // to the connection's lifetime: between cycles there is none, and the
                    // panel would show 已断开 for almost the whole run of a healthy consumer.
                    // The status means "the cycle's read path works", so only a failed cycle
                    // drops it; a healthy cycle leaves the panel green until the next poll.
                    stats.setConnected(false);
                } finally {
                    closeQuietly(currentSource);
                    currentSource = null;
                }
                if (!running) {
                    break;
                }
                Throwable failure = lastFailure.get();
                if (failure == null) {
                    // F-10 counts CONSECUTIVE failed cycles. A cycle that completed
                    // without an exception — even one that drained zero rows and flushed
                    // no transaction — proves the connect/read path is healthy, so the
                    // old tally must go. Clearing it only in flushTransaction meant an
                    // idle tracked table never cleared it: transient blips accumulated
                    // forever and eventually parked a perfectly healthy project.
                    applyFailures.set(0);
                }
                CdcFailureClassifier.Kind kind = failure == null
                        ? CdcFailureClassifier.Kind.TRANSIENT
                        : CdcFailureClassifier.classify(failure);
                if (kind != CdcFailureClassifier.Kind.TRANSIENT
                        || parseStrikeOut() || applyStrikeOut()) {
                    fatalStop(projectId, failure, kind);
                    break;
                }
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (dwellMs >= CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS) {
                    backoff.reset();
                }
                // 繁忙轮(捞到数据)立即开下一轮;只有真正空闲才休眠 idlePollMs,失败走指数退避。
                long delay = CdcReconnectBackoff.nextCycleDelayMs(
                        failure, rowsDrained > 0, backoff, idlePollMs);
                // lastError feeds the project panel's "last error" row, which the template
                // renders only when non-null. A healthy cycle must leave it null, and its
                // completion line belongs at DEBUG: idle polling runs every 500 ms by
                // default, so logging it at WARN flooded the log of every healthy project.
                if (failure == null) {
                    stats.setLastError(null);
                    log.debug("PG cycle for project {} drained; next cycle in {} ms",
                            projectId, delay);
                } else {
                    String detail = CdcFailureClassifier.describe(failure);
                    stats.setLastError(detail);
                    log.warn("PG cycle for project {} failed ({}); next cycle in {} ms",
                            projectId, detail, delay);
                }
                if (!sleepWhileRunning(delay)) {
                    break;
                }
            }
        } catch (RuntimeException | Error | SQLException e) {
            // SQLException 只可能来自基线前的 ensureSlot 短连接(F-8):建槽失败=无法安全
            // 基线,与 runBaseline 读位点失败同等待遇 —— park ERROR,交监督/人工重启。
            if (e instanceof LockLostException) {
                // R1: the baseline lost the lease to another node; that node owns the
                // position row and will redo the baseline. Quiet exit, never ERROR.
                log.info("PG consumer for project {} stopped: lease lost during the"
                        + " baseline ({}); not parking it in ERROR", projectId, e.toString());
            } else if (!running || Thread.currentThread().isInterrupted()) {
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
            windowLogger.close();
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
     * F-11: read via PEEK (non-consuming). The options argument is VARIADIC text[],
     * which a PreparedStatement cannot bind directly (a bound text parameter does not
     * coerce to text[]). The option names/values are internal constants, so inline-quoting
     * them is injection-safe. The slot name parameter needs an explicit ::name cast — the
     * driver binds it as varchar, and PG does not implicitly coerce varchar to name when
     * the call also carries a VARIADIC array (overload resolution fails).
     *
     * <p>The {@code max_changes} argument is a hint, not a hard cut: logical decoding never
     * emits half a transaction — if the next complete transaction would exceed the limit,
     * only already-decoded transactions are returned (empirically, limit=2 still returned
     * a 7-row transaction). Hence every peeked transaction is flushable as a whole.
     */
    static final String PEEK_CHANGES_SQL =
            "SELECT lsn::text, data FROM pg_logical_slot_peek_changes("
                    + "?::name, NULL::pg_lsn, ?::integer,"
                    + " VARIADIC ARRAY['include-xids', '1']::text[])";

    /**
     * F-11: the slot is confirmed explicitly, one applied transaction at a time. The
     * second parameter is cast ::pg_lsn because the driver binds the string as varchar.
     * Advancing to the transaction's COMMIT LSN is exactly right: confirmed_flush is
     * exclusive, and the next transaction's BEGIN may carry that same LSN and is still
     * delivered.
     */
    static final String ADVANCE_SLOT_SQL =
            "SELECT end_lsn::text FROM pg_replication_slot_advance("
                    + "?::name, ?::pg_lsn)";

    /**
     * Drain one cycle: PEEK the slot inside ONE explicit source transaction, buffer per
     * transaction, and as each COMMIT line arrives flush the COMPLETE transaction to the
     * target, then — and only then — move the slot forward explicitly with
     * pg_replication_slot_advance. Returns the total row count processed (for backoff
     * decisions).
     *
     * <p><b>F-11 — why peek + explicit advance:</b> docker 实证(postgres:16) the slot
     * advance performed by pg_logical_slot_get_changes is NOT tied to the enclosing
     * transaction — confirmed_flush_lsn moves the instant the function runs and is not
     * rewound by ROLLBACK. pg_replication_slot_advance behaves the same way. Relying on
     * rollback to re-read a poisoned transaction (a target missing the new column,
     * SQLSTATE 42703) therefore lost the transaction after its single failed attempt:
     * every later cycle peeked an empty slot forever and the row was silently dropped.
     * With non-consuming PEEK plus advance-after-commit, an unflushed transaction is
     * re-peeked verbatim next cycle; idempotent upserts absorb replays (at-least-once,
     * data before position), and the F-10 strike-out correctly parks a persistently
     * poisonous stream instead of spinning on it.
     *
     * <p>We never pass a start_lsn — peek always reads from the slot's confirmed position.
     * The slot is created in {@link #run()} BEFORE the baseline full load (F-8): it retains
     * every WAL change from that point on. Transactions committed while the baseline runs
     * may arrive twice (baseline row + slot event); the overlap is absorbed by idempotent
     * upserts, and transactions committed after the baseline snapshot exist only in the
     * slot — lazily creating the slot after the baseline would lose them permanently.
     *
     * <p>setAutoCommit(false) is kept because PG JDBC cursor streaming requires it; the
     * source commit/rollback no longer has any effect on slot position (F-11). An empty
     * transaction (BEGIN immediately followed by COMMIT) is still advanced — otherwise it
     * would be re-peeked forever and the slot would never move.
     */
    int drain(Connection conn, String slotName) throws Exception {
        long projectId = project.getId();
        int batchSize = (int) Math.min(Integer.MAX_VALUE, properties.getCdc().getPg().getBatchSize());
        int total = 0;
        Map<CdcTableBinding, List<Map<String, Object>>> upserts = new LinkedHashMap<>();
        Map<CdcTableBinding, List<Map<String, Object>>> deletes = new LinkedHashMap<>();
        String lastCommitLsn = null;
        String currentTxLsn = null; // LSN of the latest row in the buffered transaction
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int rowsThisCall = 0;
                try (PreparedStatement ps = conn.prepareStatement(PEEK_CHANGES_SQL)) {
                    ps.setString(1, slotName);
                    ps.setLong(2, batchSize);
                    ps.setFetchSize(batchSize);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                            String lsn = rs.getString(1);
                            String data = rs.getString(2);
                            PgTestDecodingParser.SlotLine line = parser.parse(data);
                            rowsThisCall++;
                            total++;
                            if (line.txStart) {
                                // F-11:BEGIN means the whole transaction starts here. Peek
                                // never returns a half transaction, but discarding any
                                // stale buffer makes a defensive re-peek (or any server
                                // quirk) idempotent — buffered rows must never be flushed
                                // as part of the wrong transaction.
                                upserts.clear();
                                deletes.clear();
                                resetSpiller();
                                currentTxLsn = null;
                                continue;
                            }
                            if (line.txEnd) {
                                // Commit boundary — flush, then confirm the slot exactly
                                // to the COMMIT record's LSN (this row), not the last
                                // data row: confirmed_flush must cover the commit record,
                                // otherwise the transaction is re-decoded next peek.
                                // Empty transactions are confirmed too.
                                String commitLsn = lsn;
                                if (!upserts.isEmpty() || !deletes.isEmpty()) {
                                    flushTransaction(projectId, commitLsn, upserts, deletes);
                                    upserts.clear();
                                    deletes.clear();
                                }
                                resetSpiller();
                                advanceSlot(conn, slotName, commitLsn);
                                lastCommitLsn = commitLsn;
                                currentTxLsn = null;
                                continue;
                            }
                            PgTestDecodingParser.ParsedChange change = line.change;
                            if (change == null || change.isSkipped()) {
                                PgTestDecodingParser.Skip skipKind =
                                        change == null ? null : change.skip;
                                String reason = change == null
                                        ? "null" : change.unsupportedReason;
                                if (skipKind == PgTestDecodingParser.Skip.IGNORABLE) {
                                    // Not a change line at all: logical messages (any
                                    // client on the source DB can emit them — the slot
                                    // decodes the whole database), two-phase bookkeeping,
                                    // noise. Counting them as parse failures used to park
                                    // a perfectly healthy project after 6 heartbeats while
                                    // the synced tables were idle (the counter only clears
                                    // on a successful flush). Ignore them silently.
                                    log.debug("Ignoring PG slot line: {} (sample: {})",
                                            reason, truncate(data, 120));
                                    continue;
                                }
                                if (skipKind == PgTestDecodingParser.Skip.VALUE_UNAVAILABLE) {
                                    // test_decoding cannot reproduce an unmodified TOASTed
                                    // column, so the value is genuinely absent from the
                                    // stream — the same class as LogMiner's out-of-line LOBs
                                    // and reported the same way: per-table tally on the CDC
                                    // panel, one throttled warning instead of one per row
                                    // (a hot table would otherwise flood the log and drown
                                    // the rest). Rows of tables this project does not mirror
                                    // are not this project's loss and are not counted at all.
                                    if (change.qualifiedTable != null
                                            && !matchesProjectTable(change.qualifiedTable)) {
                                        continue;
                                    }
                                    String tableKey = change.qualifiedTable == null
                                            ? "(unknown table)" : change.qualifiedTable;
                                    CdcRuntimeStats.MissingLobHit hit = stats.recordMissingLob(
                                            tableKey,
                                            properties.getLob().getMetricsLogIntervalMs());
                                    if (hit.warnDue()) {
                                        log.warn("{} change(s) on {} skipped so far: {} — the"
                                                + " value is not in the logical stream, so the"
                                                + " target keeps its previous version; run a"
                                                + " full sync of the table to catch up.",
                                                hit.count(), tableKey, reason);
                                    } else {
                                        log.debug("Skipping PG slot row on {}: {}",
                                                tableKey, reason);
                                    }
                                    stats.addSkippedUnsupported(1);
                                    parseFailures.incrementAndGet();
                                    continue;
                                }
                                stats.addSkippedUnsupported(1);
                                if (skipKind == PgTestDecodingParser.Skip.UNSUPPORTED_EVENT) {
                                    // A real change of a kind we do not mirror (TRUNCATE,
                                    // …): surface and count it, but the stream is being
                                    // decoded — not a strike-out case.
                                    log.warn("Skipping PG slot row: {} (sample: {})",
                                            reason, truncate(data, 120));
                                    continue;
                                }
                                parseFailures.incrementAndGet();
                                log.warn("Skipping PG slot row: {} (sample: {})",
                                        reason, truncate(data, 120));
                                continue;
                            }
                            // H3 analog (LogMinerCdcConsumer): a row that decoded proves the
                            // stream shape is understood, so it breaks the run of consecutive
                            // poison rows even before the transaction flushes.
                            parseFailures.set(0);
                            if (!matchesProjectTable(change.qualifiedTable)) {
                                continue;
                            }
                            Resolution resolved =
                                    supplier.resolve(change.qualifiedTable);
                            if (resolved.binding != null && !resolved.describes(change)) {
                                // The slot stream carries no DDL (test_decoding emits row
                                // changes only), so the row shape is the only signal that the
                                // cached metadata went stale — typically a source ADD COLUMN
                                // after the binding was read. Without this, the upsert is
                                // built from the old column list and the new column's value
                                // is dropped silently and permanently.
                                log.info("PG binding for {} no longer matches the change row"
                                        + " shape (source DDL?); re-reading metadata",
                                        change.qualifiedTable);
                                resolved = supplier.refresh(change.qualifiedTable);
                            }
                            CdcTableBinding binding = resolved.binding;
                            if (binding == null || !binding.isApplicable()) {
                                stats.addUnsupportedTable(change.qualifiedTable);
                                continue;
                            }
                            currentTxLsn = lsn;
                            if (change.op == PgTestDecodingParser.Op.DELETE) {
                                appendDelete(upserts, deletes, binding, change.row);
                            } else {
                                // Only upserts can carry large payloads to the target; a
                                // delete binds primary keys alone and stays in memory.
                                appendUpsert(upserts, deletes, binding,
                                        spiller().spill(binding, change.row));
                                if (change.oldImage != null
                                        && CdcPrimaryKey.changed(change.oldImage, change.row, binding)) {
                                    // The key moved: the new image cannot locate the old row,
                                    // so delete it explicitly or it stays on the target forever.
                                    appendDelete(upserts, deletes, binding, change.oldImage);
                                }
                            }
                            stats.touchEvent(System.currentTimeMillis());
                        }
                    }
                }
                if (!running || Thread.currentThread().isInterrupted()) {
                    break; // shutdown mid-drain; the slot was not advanced
                }
                boolean holdsHalfTransaction = !upserts.isEmpty() || !deletes.isEmpty();
                if (holdsHalfTransaction) {
                    if (rowsThisCall < batchSize) {
                        // Defensive only: peek cannot emit a half transaction (F-11).
                        // If this ever fires, nothing was flushed or advanced for the
                        // partial buffer; next cycle re-peeks the whole transaction.
                        log.warn("PG slot for project {} returned a half transaction ({}"
                                + " buffered rows discarded, slot not advanced);"
                                + " re-peeking next cycle",
                                projectId, upserts.size() + deletes.size());
                        break;
                    }
                    continue; // budget cut mid-stream: peek again (complete txns only)
                }
                if (total >= batchSize || rowsThisCall < batchSize) {
                    break; // cycle budget spent at a tx boundary, or slot empty
                }
            }
            boolean clean = running && !Thread.currentThread().isInterrupted()
                    && upserts.isEmpty() && deletes.isEmpty();
            // Source commit/rollback only closes the streaming transaction — slot
            // position is unaffected either way (F-11). Rollback on a non-clean exit
            // releases the cursor transaction without having advanced anything.
            if (clean) {
                conn.commit();
            } else {
                conn.rollback();
            }
        } catch (Exception e) {
            try {
                // The failed transaction was never advanced (F-11); rollback only
                // releases the cursor transaction. Next cycle re-peeks the same rows.
                conn.rollback();
            } catch (SQLException | RuntimeException rollbackFailure) {
                log.warn("Rollback of the PG cursor transaction failed for project {}"
                        + " (connection teardown follows anyway): {}",
                        projectId, rollbackFailure.getMessage());
            }
            throw e;
        } finally {
            // A half transaction is re-peeked from the slot: its spilled files have no
            // reader left in this process.
            resetSpiller();
            try {
                conn.setAutoCommit(originalAutoCommit);
            } catch (SQLException | RuntimeException ignored) {
                // connection is being closed by the caller anyway
            }
        }
        if (lastCommitLsn != null) {
            stats.recordPositionLsn(lastCommitLsn);
        }
        return total;
    }

    /** This transaction's spiller; one per buffered transaction, disposed on its boundary. */
    private CdcLobSpiller spiller() {
        if (spiller == null) {
            LobSpillStore store = spillStore();
            spiller = store == null ? CdcLobSpiller.disabled()
                    : new CdcLobSpiller(store, properties.getLob().getCdcSpillThresholdBytes(),
                            ctx.getConfig());
        }
        return spiller;
    }

    private void resetSpiller() {
        if (spiller != null) {
            spiller.close();
            spiller = null;
        }
    }

    private LobSpillStore spillStore() {
        if (!properties.getLob().isStreamingEnabled()) {
            return null;
        }
        LobSpillStore store = lobSpillStore;
        if (store == null) {
            synchronized (this) {
                store = lobSpillStore;
                if (store == null) {
                    store = LobSpillStore.from(properties);
                    lobSpillStore = store;
                }
            }
        }
        return store;
    }

    /**
     * F-11: confirm one applied transaction on the source slot. Called ONLY after the
     * target commit and the local position advance succeeded — pg_replication_slot_advance
     * takes effect immediately and is not rolled back with the enclosing transaction
     * (docker 实证), so calling it earlier would silently drop events.
     *
     * <p>Runs on the same connection that is streaming the peek: it uses its own
     * PreparedStatement/ResultSet, which PG JDBC supports alongside the open cursor.
     */
    private void advanceSlot(Connection conn, String slotName, String commitLsn)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ADVANCE_SLOT_SQL)) {
            ps.setString(1, slotName);
            ps.setString(2, commitLsn);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // result row reports the end LSN the slot actually reached
            }
        }
    }

    private void flushTransaction(long projectId, String commitLsn,
                                  Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                  Map<CdcTableBinding, List<Map<String, Object>>> deletes)
            throws Exception {
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
        positionStore.advanceLsn(projectId, commitLsn);
        stats.recordPositionLsn(commitLsn);
        parseFailures.set(0);
        // F-10:一个事务完整落库即证明毒事务已消失(DBA 补了列/目标恢复),连续失败
        // 计数必须清零 —— 否则偶发几次抖动后长期健康运行也会被老账 park。
        applyFailures.set(0);
    }

    /**
     * Same-PK folding inside one transaction, the rule {@code CdcEventStreamHandler}'s
     * FIX C and {@link SqlServerTxFold} already apply: the flusher runs every upsert
     * before every delete (that order is what makes PK chains A→B, B→C land correctly),
     * so a buffer holding an upsert AND a delete for one key applies them in the wrong
     * order. A DELETE followed by an INSERT of the same key (REPLACE) is the common
     * shape, and unfolded it leaves the target row deleted while the source has it.
     * Appending an upsert therefore drops any buffered delete of that key, and vice versa.
     */
    private static void appendUpsert(Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                     Map<CdcTableBinding, List<Map<String, Object>>> deletes,
                                     CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, binding);
        List<Map<String, Object>> buffered = deletes.get(binding);
        if (buffered != null) {
            buffered.removeIf(bufferedRow -> CdcPrimaryKey.of(bufferedRow, binding).equals(key));
        }
        upserts.computeIfAbsent(binding, k -> new ArrayList<>()).add(row);
    }

    private static void appendDelete(Map<CdcTableBinding, List<Map<String, Object>>> upserts,
                                     Map<CdcTableBinding, List<Map<String, Object>>> deletes,
                                     CdcTableBinding binding, Map<String, Object> row) {
        List<Object> key = CdcPrimaryKey.of(row, binding);
        List<Map<String, Object>> buffered = upserts.get(binding);
        if (buffered != null) {
            buffered.removeIf(bufferedRow -> CdcPrimaryKey.of(bufferedRow, binding).equals(key));
        }
        deletes.computeIfAbsent(binding, k -> new ArrayList<>()).add(row);
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

    /**
     * P2-9: the SELECT-then-CREATE below is a TOCTOU race — two nodes of the same
     * project starting together both see "no slot", one CREATE wins, the other eats
     * 42710 duplicate_object. That exception used to abort the drain and burn a whole
     * backoff cycle before the retry noticed the slot now exists. 42710 means the
     * slot exists, which is exactly the state we wanted: swallow at DEBUG and move on.
     * Any other SQLState (42501 permission, 55000 config, …) is a real error and
     * must propagate — swallowing it would spin the consumer on a slot that can
     * never appear.
     */
    static void ensureSlot(Connection conn, String slotName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pg_replication_slots WHERE slot_name = '"
                             + slotName.replace("'", "''") + "'")) {
            if (rs.next()) {
                return;
            }
        }
        log.info("Creating PG logical replication slot '{}'", slotName);
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT pg_create_logical_replication_slot('"
                    + slotName.replace("'", "''") + "', 'test_decoding')");
        } catch (SQLException e) {
            if ("42710".equals(e.getSQLState())) {
                log.debug("slot '{}' created concurrently (42710 duplicate_object)"
                        + " — treating as already present", slotName);
                return;
            }
            throw e;
        }
    }

    /**
     * P2-10: PG accepts only [a-z0-9_] in a replication slot name. Failing fast at
     * name composition beats shipping an escaped name that PG then rejects at CREATE
     * time with its own error — this points at the misconfiguration directly.
     */
    static boolean isSafeSlotName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    static String slotName(long projectId, SyncProperties properties) {
        String prefix = properties.getCdc().getPg().getSlotPrefix();
        String name = (prefix == null ? "jync_" : prefix) + projectId;
        if (!isSafeSlotName(name)) {
            throw new IllegalStateException("PG replication slot name '" + name
                    + "' is outside PostgreSQL's [a-z0-9_] whitelist — fix"
                    + " sync.cdc.pg.slot-prefix to lowercase letters/digits/underscore");
        }
        return name;
    }

    /**
     * Best-effort slot release for the project-delete path (P0-5). A replication slot
     * outlives the process that created it: left behind, the source keeps WAL for it
     * forever and the disk fills on account of a project nobody can see anymore.
     *
     * <p>Never throws — an unreachable source must not block the local delete; the
     * failure is a WARN a DBA can act on (drop the slot by hand). Callers own the
     * connection's lifecycle.
     */
    public static void dropSlotQuietly(Connection conn, long projectId, SyncProperties properties) {
        // slotName() inside the try: P2-10 validation throws IllegalStateException for
        // an off-whitelist prefix, and the "never blocks a project delete" contract
        // still holds — the WARN names the prefix instead of the slot.
        String slotName = null;
        try {
            slotName = slotName(projectId, properties);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('"
                        + slotName.replace("'", "''") + "')");
                log.info("Released PG replication slot '{}' of deleted project {}",
                        slotName, projectId);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not release PG replication slot '{}' on the source (project {}"
                    + " deleted anyway): {} — drop it by hand or the source WAL will grow"
                    + " without bound", slotName, projectId, e.getMessage());
        }
    }

    private boolean parseStrikeOut() {
        return parseFailures.get() > MAX_PARSE_FAILURES;
    }

    /** F-10:> 5 consecutive failing drain cycles with no applied transaction between. */
    private boolean applyStrikeOut() {
        return applyFailures.get() > MAX_PARSE_FAILURES;
    }

    private void fatalStop(long projectId, Throwable failure, CdcFailureClassifier.Kind kind) {
        String label;
        if (kind != CdcFailureClassifier.Kind.TRANSIENT) {
            label = kind.name();
        } else if (applyStrikeOut()) {
            // 每周期都失败但分类 TRANSIENT(典型:目标表缺列,42703):重试在同一毒
            // 事务上空转,等人处置结构漂移。
            label = "APPLY_STRIKE_OUT";
        } else {
            label = "PARSE_STRIKE_OUT";
        }
        String detail = label + ": " + (failure == null
                ? "repeated PG failures"
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

        /**
         * Cached resolutions keyed by "{@code schema.table}"; a table that cannot be applied
         * is cached too (with a null binding): without that, every row event of a no-PK/hot
         * table re-opens a source connection, re-reads metadata and logs the same WARN — the
         * exact storm SqlServerCdcConsumer's sentinel prevents.
         */
        private final Map<String, Resolution> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, Long> refreshedAt = new ConcurrentHashMap<>();

        Resolution resolve(String qualified) {
            Resolution cached = cache.get(qualified);
            if (cached != null && !cached.recheckDue(System.currentTimeMillis())) {
                return cached;
            }
            return read(qualified);
        }

        /** Re-read after the stream contradicted the cached column list (see the caller). */
        Resolution refresh(String qualified) {
            long now = System.currentTimeMillis();
            Long last = refreshedAt.get(qualified);
            if (last != null && now - last < BINDING_REFRESH_COOLDOWN_MS) {
                Resolution cached = cache.get(qualified);
                if (cached != null) {
                    return cached;
                }
            }
            refreshedAt.put(qualified, now);
            return read(qualified);
        }

        private Resolution read(String qualified) {
            int dot = qualified.indexOf('.');
            String schema = dot < 0 ? "public" : qualified.substring(0, dot);
            String table = dot < 0 ? qualified : qualified.substring(dot + 1);
            Resolution resolution;
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn, schema, table);
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("PG cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", schema, table);
                    resolution = Resolution.unmappable(System.currentTimeMillis());
                } else {
                    CdcTableBinding b = CdcTableBinding.of(schema, table, meta,
                            ctx.getConfig().targetTableName(meta.getName()),
                            Collections.emptyMap());
                    resolution = Resolution.of(b);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "PG metadata read failed for " + qualified, e);
            }
            cache.put(qualified, resolution);
            return resolution;
        }
    }

    /**
     * One table's cached metadata plus the column set it describes. The caller compares that
     * set against each decoded row: logical replication carries no DDL, so a row whose
     * columns disagree with the cache is the only evidence that the source schema moved.
     */
    static final class Resolution {
        final CdcTableBinding binding;
        private final Set<String> columns;
        /** When an "unmappable" verdict was formed; unused for a real binding. */
        private final long createdAtMillis;

        private Resolution(CdcTableBinding binding, Set<String> columns, long createdAtMillis) {
            this.binding = binding;
            this.columns = columns;
            this.createdAtMillis = createdAtMillis;
        }

        static Resolution of(CdcTableBinding binding) {
            return new Resolution(binding, new LinkedHashSet<>(binding.columnNames()), 0L);
        }

        static Resolution unmappable(long createdAtMillis) {
            return new Resolution(null, Collections.emptySet(), createdAtMillis);
        }

        /** See {@link #BINDING_UNMAPPABLE_RECHECK_MS}. */
        boolean recheckDue(long nowMillis) {
            return binding == null && nowMillis - createdAtMillis >= BINDING_UNMAPPABLE_RECHECK_MS;
        }

        /**
         * INSERT and UPDATE carry every live column of the table (test_decoding prints the
         * whole new tuple), so the sets must be equal — that is what catches both an added
         * and a dropped column. A DELETE under REPLICA IDENTITY DEFAULT carries the key
         * columns only, so it only has to be a subset.
         */
        boolean describes(PgTestDecodingParser.ParsedChange change) {
            if (binding == null) {
                return true;
            }
            return change.op == PgTestDecodingParser.Op.DELETE
                    ? columns.containsAll(change.row.keySet())
                    : columns.equals(change.row.keySet());
        }
    }
}
