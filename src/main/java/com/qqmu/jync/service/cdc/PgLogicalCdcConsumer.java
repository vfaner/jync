package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * ({@code jync_<projectId>}) exists. Stopping does NOT drop it — re-enabling would then
 * need a fresh baseline, and the slot is the resume watermark. The paths that leave it
 * with no consumer at all (project deleted, strategy switched away, source changed)
 * release it best-effort through {@link #dropSlotQuietly}: an orphaned slot retains WAL
 * forever and fills the source disk.
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
    /**
     * Server dialect, decided per connection from what the server's catalog can do (the
     * product name is only the fallback — see {@link PgLogicalDialect#detectLive}).
     * Defaults to vanilla PostgreSQL so a failed capability read can only reproduce the
     * pre-v1.9.0 behavior.
     */
    private volatile PgLogicalDialect dialect = PgLogicalDialect.POSTGRES;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    /**
     * Newest LSN whose transaction is committed to the target. Non-null means the source
     * slot must already exist: a missing slot at that point (see {@code ensureSlot}) means
     * the log between this position and now is unrecoverable.
     */
    private volatile String committedLsn;
    /**
     * Wall-clock-independent gate for the retained-WAL gauge (one query per 30 s). Volatile
     * because the baseline-time sampler thread shares it with the drain loop: the two never
     * overlap by design, but a sampler that overruns its stop join must not corrupt a read.
     */
    private volatile long lastWalSampleNs;
    private static final long WAL_SAMPLE_INTERVAL_NS = 30_000_000_000L;
    /**
     * Cadence of the baseline-time sampler. `sampleRetainedWal` applies the same 30 s gate
     * itself, so this only decides how quickly the thread notices an interrupt.
     */
    private static final long BASELINE_WAL_SAMPLE_INTERVAL_MS = 30_000L;
    private final AtomicInteger parseFailures = new AtomicInteger();
    // F-10:连续失败周期计数(drain 抛异常的周期 +1,成功 flush 一个事务清零)。
    // 只覆盖解析失败的 parseFailures 拦不住「每周期必然失败的目标端写入」——典型是
    // 源库停机期 ADD COLUMN 而目标表没有该列:openGauss 42703 分类为 TRANSIENT,
    // 旧代码空转无限重试,既不落数据也不 park。
    private final AtomicInteger applyFailures = new AtomicInteger();
    /**
     * Whether THIS cycle failed while applying to the target. Set only around a target
     * flush (see {@link #drain}), so a source-side read/stream failure mid-drain retries
     * with backoff instead of accumulating towards APPLY_STRIKE_OUT.
     */
    private boolean applyFailedThisCycle;
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    /** Per-table aggregated change-log flusher (10s windows, final flush on stop). */
    private final CdcChangeWindowLogger windowLogger;

    /** LOB spill store for buffered rows, built on first use; null when switched off. */
    private final LazyLobSpillStore lobSpillStore = new LazyLobSpillStore();
    /** Owns the current transaction's spilled values; reset on every boundary. */
    private final CdcSpillerHolder spillers = new CdcSpillerHolder();

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
                    dialect = dialectOf(slotConn);
                    // No baselineDone position can exist on this branch, so a missing slot
                    // is expected and gets created; pass null rather than a stale LSN.
                    ensureSlot(slotConn, slotName, null);
                }
                // The drain loop samples the slot's retained WAL once per cycle, but the
                // chunked full load below can run for hours without reaching that loop. A
                // slot that stops advancing during the load pins WAL on the source — the
                // one failure this gauge exists to surface — so the baseline gets its own
                // sampler. Always stopped here: after the load the main loop samples again.
                Thread walSampler = startBaselineWalSampler(slotName,
                        BASELINE_WAL_SAMPLE_INTERVAL_MS, dialect);
                try {
                    baselineService.runBaseline(ctx, lock::renew);
                } finally {
                    stopBaselineWalSampler(walSampler);
                }
                position = positionStore.find(projectId).orElse(null);
            }
            if (position == null || position.getLsn() == null) {
                throw new IllegalStateException("PG logical baseline completed without a"
                        + " recorded LSN anchor; refusing to read from an unknown position");
            }
            committedLsn = position.getLsn();
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
                // F-10 counts APPLY failures only. drain marks this when a target flush
                // fails; a source read/stream failure mid-drain leaves it false, so it
                // retries with backoff (the same rule as "source unreachable"). Counting
                // every drain exception parked the project after ~30 s of network trouble.
                applyFailedThisCycle = false;
                int rowsDrained;
                try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                    currentSource = conn;
                    dialect = dialectOf(conn);
                    ensureSlot(conn, slotName, committedLsn);
                    sampleRetainedWal(conn, slotName, dialect);
                    stats.setConnected(true);
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    rowsDrained = drain(conn, slotName);
                } catch (Throwable e) {
                    lastFailure.compareAndSet(null, e);
                    rowsDrained = 0;
                    if (applyFailedThisCycle) {
                        applyFailures.incrementAndGet();
                    }
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
                if (!CdcWaits.sleepWhileRunning(() -> running, delay)) {
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
     * Resolves the server dialect from the live connection. Any catalog/metadata failure
     * keeps the vanilla-PostgreSQL behavior, which is exactly the pre-v1.9.0 code path.
     *
     * <p>Delegates to {@link PgLogicalDialect#detectLive(Connection)}: the server's
     * capabilities decide (openGauss lacks the {@code pg_lsn} type), with the product name
     * and the {@code SELECT version()} banner only as the fallback — openGauss's official
     * driver reports the product name "PostgreSQL".
     */
    static PgLogicalDialect dialectOf(Connection conn) {
        return PgLogicalDialect.detectLive(conn);
    }

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
        CdcTransactionBuffer tx = new CdcTransactionBuffer();
        CdcTransactionBudget budget = CdcTransactionBudget.from(properties);
        String lastCommitLsn = null;
        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int rowsThisCall = 0;
                try (PreparedStatement ps = conn.prepareStatement(dialect.peekChangesSql())) {
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
                                tx.clear();
                                budget.reset();
                                resetSpiller();
                                continue;
                            }
                            if (line.txEnd) {
                                // Commit boundary — flush, then confirm the slot exactly
                                // to the COMMIT record's LSN (this row), not the last
                                // data row: confirmed_flush must cover the commit record,
                                // otherwise the transaction is re-decoded next peek.
                                // Empty transactions are confirmed too.
                                String commitLsn = lsn;
                                if (!tx.isEmpty()) {
                                    try {
                                        flushTransaction(projectId, commitLsn, tx);
                                    } catch (Exception e) {
                                        applyFailedThisCycle = true; // 写目标端失败才计入熔断
                                        throw e;
                                    }
                                    tx.clear();
                                    budget.reset();
                                }
                                resetSpiller();
                                advanceSlot(conn, slotName, commitLsn);
                                lastCommitLsn = commitLsn;
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
                                    // Deliberately NOT a parse failure: nothing failed to
                                    // decode. Counting it here parked healthy projects — the
                                    // tally only clears on a successful flush, so six such
                                    // rows (one hot table's worth) ended the run with
                                    // PARSE_STRIKE_OUT and stopped every table in the project.
                                    // A structural, understood skip belongs on the panel as a
                                    // per-table tally, not on the strike-out path.
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
                            if (change.op == PgTestDecodingParser.Op.DELETE) {
                                tx.delete(binding, change.row);
                                budget.add(change.row);
                            } else {
                                // Only upserts can carry large payloads to the target; a
                                // delete binds primary keys alone and stays in memory.
                                tx.upsert(binding, spiller().spill(binding, change.row));
                                budget.add(change.row);
                                if (change.oldImage != null
                                        && CdcPrimaryKey.changed(change.oldImage, change.row, binding)) {
                                    // The key moved: the new image cannot locate the old row,
                                    // so delete it explicitly or it stays on the target forever.
                                    tx.delete(binding, change.oldImage);
                                    budget.add(change.oldImage);
                                }
                            }
                            stats.touchEvent(System.currentTimeMillis());
                        }
                    }
                }
                if (!running || Thread.currentThread().isInterrupted()) {
                    break; // shutdown mid-drain; the slot was not advanced
                }
                boolean holdsHalfTransaction = !tx.isEmpty();
                if (holdsHalfTransaction) {
                    if (rowsThisCall < batchSize) {
                        // Defensive only: peek cannot emit a half transaction (F-11).
                        // If this ever fires, nothing was flushed or advanced for the
                        // partial buffer; next cycle re-peeks the whole transaction.
                        log.warn("PG slot for project {} returned a half transaction ({}"
                                + " buffered rows discarded, slot not advanced);"
                                + " re-peeking next cycle",
                                projectId, tx.size());
                        break;
                    }
                    continue; // budget cut mid-stream: peek again (complete txns only)
                }
                if (total >= batchSize || rowsThisCall < batchSize) {
                    break; // cycle budget spent at a tx boundary, or slot empty
                }
            }
            boolean clean = running && !Thread.currentThread().isInterrupted()
                    && tx.isEmpty();
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
        return spillers.get(ctx, properties, spillStore());
    }

    private void resetSpiller() {
        spillers.reset();
    }

    /** The LOB spill store, or {@code null} when streaming is switched off. */
    private LobSpillStore spillStore() {
        return lobSpillStore.get(properties);
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
        try (PreparedStatement ps = conn.prepareStatement(dialect.advanceSlotSql())) {
            ps.setString(1, slotName);
            ps.setString(2, commitLsn);
            try (ResultSet rs = ps.executeQuery()) {
                // The result row reports the end LSN the slot actually reached. No row means
                // the slot did NOT move: continuing would re-peek the same transaction every
                // cycle while the local position advanced, with nothing on the panel to show
                // it. Fail the cycle so it retries (at-least-once, idempotent upserts) and
                // the error becomes visible — probe ⑤ refuses such a server up front, so
                // this is the runtime backstop.
                if (!rs.next()) {
                    throw new SQLException("pg_replication_slot_advance returned no row for"
                            + " slot '" + slotName + "' at " + commitLsn
                            + " — the slot did not advance");
                }
            }
        }
    }

    private void flushTransaction(long projectId, String commitLsn, CdcTransactionBuffer tx)
            throws Exception {
        Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig());
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e
                    : tx.upserts().entrySet()) {
                applier.applyUpserts(target, e.getKey(), e.getValue());
            }
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e
                    : tx.deletes().entrySet()) {
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
        // Record the anchor only AFTER the target commit and the lease re-check: from this
        // point the slot must keep existing, so the next cycle's ensureSlot treats a missing
        // slot as an unrecoverable gap instead of quietly creating one at "now".
        committedLsn = commitLsn;
        stats.recordPositionLsn(commitLsn);
        parseFailures.set(0);
        // F-10:一个事务完整落库即证明毒事务已消失(DBA 补了列/目标恢复),连续失败
        // 计数必须清零 —— 否则偶发几次抖动后长期健康运行也会被老账 park。
        applyFailures.set(0);
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
    /**
     * @param committedLsn the position already committed to the target, or null/blank
     *                     before the first commit; see the purge guard below
     */
    static void ensureSlot(Connection conn, String slotName, String committedLsn)
            throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pg_replication_slots WHERE slot_name = '"
                             + slotName.replace("'", "''") + "'")) {
            if (rs.next()) {
                return;
            }
        }
        // A slot that vanished while a committed position exists means the WAL between that
        // position and now is already applied to the source but will never be read: the
        // replacement slot starts at "now" and the stored LSN gets advanced from the new
        // stream, so the gap is invisible and permanent. Creating the slot here (the old
        // behaviour) turned that into a silently lossy project, so it parks instead.
        // Triggers: operator cleanup, openGauss restart without enable_slot_log, a restored
        // standby, or a WAL reset. The remedy is the same as every other PURGED case —
        // rebuild the baseline from a fresh snapshot.
        if (committedLsn != null && !committedLsn.isBlank()) {
            throw new SQLException("PG cdc purge: replication slot '" + slotName
                    + "' no longer exists on the source, but the project already committed"
                    + " changes up to LSN " + committedLsn.trim()
                    + ". Every change committed after that position is missing and cannot be"
                    + " recovered from the log — rebuild the CDC baseline from a fresh"
                    + " snapshot before capturing again.");
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
     * Sample the slot's retained WAL at most once per interval from a small daemon thread
     * that lives only as long as the baseline, or null when nothing can be measured.
     *
     * <p>The baseline is a chunked full load that can take hours, during which the consumer
     * never reaches {@link #sampleRetainedWal}'s drain-loop call site. A slot created before
     * the load (F-8) keeps every WAL segment from that moment: if nothing consumes it — the
     * load is aborted, the target is down, writes outpace the copy — the source disk fills,
     * which takes the whole database down rather than just this project. Without this
     * thread the panel and the crossing warning stay silent for exactly that window.
     *
     * <p>Samples start after one full interval: at baseline start the gauge is near zero,
     * and sleeping first keeps the sampler's first connection off a source that is still
     * establishing the load's own connections.
     *
     * <p>openGauss (PG 9.2 lineage) has no {@code pg_lsn}, so the gauge's statement can
     * never succeed there — no thread is started at all, matching
     * {@link PgLogicalDialect#canMeasureRetainedWal()}.
     */
    Thread startBaselineWalSampler(String slotName, long intervalMs,
                                   PgLogicalDialect serverDialect) {
        if (!serverDialect.canMeasureRetainedWal()) {
            return null;
        }
        Thread sampler = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    return;                          // baseline finished: the loop takes over
                }
                sampleRetainedWalDuringBaseline(slotName, serverDialect);
            }
        }, "jync-pg-wal-" + project.getId());
        // Daemon: a stuck sample must never hold up JVM exit.
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    /** Stops the baseline-time sampler and waits a short, bounded time for it to die. */
    private void stopBaselineWalSampler(Thread sampler) {
        if (sampler == null) {
            return;
        }
        sampler.interrupt();
        try {
            sampler.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sampler.isAlive()) {
            // Its connection may be blocked in getConnection (pool busy with the load);
            // the thread is a daemon and its next sample is harmless — the gauge and the
            // once-per-crossing warning are idempotent.
            log.debug("Baseline-time WAL sampler of project {} did not stop within 5 s;"
                    + " leaving it to exit on its own", project.getId());
        }
    }

    /**
     * One baseline-time sample on its OWN source connection, so the running full load never
     * has to share (or wait for) it. Never throws: an unreachable source or an exhausted
     * pool means "no reading right now", which is exactly what a null gauge says — a
     * diagnostic must not disturb the load that is the actual job.
     */
    void sampleRetainedWalDuringBaseline(String slotName, PgLogicalDialect serverDialect) {
        Connection conn = null;
        try {
            conn = dataSourceManager.getConnection(ctx.getSourceConfig());
            sampleRetainedWal(conn, slotName, serverDialect);
        } catch (Throwable e) {
            log.debug("Baseline-time WAL sample for slot '{}' of project {} failed: {}",
                    slotName, project.getId(), e.getMessage());
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * Sample the slot's retained WAL at most once per interval.
     *
     * <p>The busy path polls every few milliseconds, and this needs a statement round-trip;
     * a slow-changing disk-watermark value does not. 30 s keeps the panel current without
     * measurable overhead on the source.
     */
    private void sampleRetainedWal(Connection conn, String slotName,
                                   PgLogicalDialect serverDialect) {
        if (!serverDialect.canMeasureRetainedWal()) {
            // openGauss has no pg_lsn type, so the gauge's statement can never succeed
            // there. Asking anyway would spend one round trip plus one log line every
            // 30 seconds, forever, on a server we already know cannot answer.
            return;
        }
        long now = System.nanoTime();
        if (now - lastWalSampleNs < WAL_SAMPLE_INTERVAL_NS) {
            return;
        }
        lastWalSampleNs = now;
        Long bytes = readRetainedWalBytes(conn, slotName);
        if (bytes == null) {
            // Transient (the slot row was just verified on this very connection): leave the
            // last value alone rather than flicker the row in and out of the panel.
            return;
        }
        long warnBytes = properties.getCdc().getPg().getWalRetentionWarnBytes();
        boolean crossed = stats.recordRetainedWal(bytes, warnBytes);
        if (crossed) {
            // The panel only helps someone looking at it. A slot that stops advancing —
            // target down, consumer parked, flush slower than the write rate — fills the
            // source disk, which takes the whole database down rather than just this
            // project, so the crossing has to reach the log too. Once per crossing, not
            // per sample: 2880 identical lines a day is noise, not a signal.
            log.warn("PG slot '{}' of project {} is holding back {} of WAL, over the {} alert"
                    + " threshold: nothing is consuming it, so the source keeps every WAL"
                    + " segment after the slot's restart point. Check the target and the"
                    + " project state; if the consumer cannot resume, rebuild the CDC baseline.",
                    slotName, project.getId(), stats.getRetainedWalLabel(),
                    CdcRuntimeStats.formatBytes(warnBytes));
        }
    }

    /**
     * How much WAL the slot is holding back, or null when the server cannot tell us.
     *
     * <p>One {@code pg_wal_lsn_diff} query, no writes: the value matters because a slot
     * that stops advancing (target down, consumer parked, very slow flush) pins every WAL
     * segment after {@code restart_lsn} and can fill the source disk — which then takes
     * the whole database down, not just this project.
     *
     * <p>Null means "unknown", never zero. openGauss has no {@code pg_lsn} type, so the
     * statement itself fails (42883 undefined_function) there; so does a missing slot row.
     * A diagnostic gauge must never fail the replication cycle.
     */
    static Long readRetainedWalBytes(Connection conn, String slotName) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)"
                             + " FROM pg_replication_slots WHERE slot_name = '"
                             + slotName.replace("'", "''") + "'")) {
            if (!rs.next()) {
                return null;
            }
            long bytes = rs.getLong(1);
            return rs.wasNull() ? null : bytes;
        } catch (SQLException e) {
            log.debug("Could not read the WAL retained by slot '{}' ({}); the panel will"
                    + " leave the row out", slotName, e.getMessage());
            return null;
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
     * Best-effort slot release for every path that leaves the slot with no consumer: the
     * project is deleted, its capture strategy moves away from PG logical decoding, or its
     * source moves to another server. A replication slot outlives the process that created
     * it — left behind, the source keeps WAL for it forever and the disk fills on account
     * of a project nobody looks at anymore.
     *
     * <p>Never throws — an unreachable source must not block the local change; the failure
     * is a WARN a DBA can act on (drop the slot by hand). Callers own the connection's
     * lifecycle, and must have stopped the consumer first: PG refuses to drop a slot that
     * is still active (55006).
     */
    public static void dropSlotQuietly(Connection conn, long projectId, SyncProperties properties) {
        // slotName() inside the try: P2-10 validation throws IllegalStateException for
        // an off-whitelist prefix, and the "never blocks the caller" contract still holds
        // — the WARN names the prefix instead of the slot.
        String slotName = null;
        try {
            slotName = slotName(projectId, properties);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('"
                        + slotName.replace("'", "''") + "')");
                log.info("Released PG replication slot '{}' of project {}", slotName, projectId);
            }
        } catch (SQLException e) {
            // 42704 undefined_object: there was nothing to drop — the project never ran
            // CDC, a DBA cleaned up already, or an earlier release did. Warning here would
            // train operators to ignore the one line that matters (a slot that still
            // exists and keeps accumulating WAL).
            if ("42704".equals(e.getSQLState())) {
                log.debug("PG replication slot '{}' of project {} is already gone —"
                        + " nothing to release", slotName, projectId);
                return;
            }
            // 55006 object_in_use: the consumer has not fully exited yet. "Drop it by
            // hand" is an instruction PG itself refuses to honour for an active slot, so
            // name the real cause instead.
            String hint = "55006".equals(e.getSQLState())
                    ? "the slot is still in use — stop the project's consumer and retry"
                    : "drop it by hand or the source WAL will grow without bound";
            log.warn("Could not release PG replication slot '{}' on the source (project {}):"
                    + " {} — {}", slotName, projectId, e.getMessage(), hint);
        } catch (RuntimeException e) {
            // slotName() validation (P2-10) throws for an off-whitelist prefix; the WARN
            // then names the prefix problem instead of a slot.
            log.warn("Could not release the PG replication slot of project {}: {}",
                    projectId, e.getMessage());
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
