package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.sync.JdbcValueNormalizer;
import com.qqmu.jync.service.sync.LockLostException;
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
 * commit LSN of the originating transaction), {@code __$seqval} (order within the
 * transaction), {@code __$operation} (1=delete, 2=insert, 3=update-before,
 * 4=update-after — an UPDATE emits a 3/4 pair), and the captured columns. We call
 * {@code cdc.fn_cdc_get_all_changes_<capture_instance>(@from_lsn, @to_lsn, 'all update old')}
 * per cycle with {@code @to_lsn = sys.fn_cdc_get_max_lsn()}, ordered by
 * {@code (__$start_lsn, __$seqval)}, and fold each transaction's rows through
 * {@link SqlServerTxFold} (P0-6): same-PK last-op-wins, and a 3/4 pair whose PK moved
 * deletes the old key — dropping op=3 there left ghost rows on the target.
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

    /**
     * F-13: consecutive failed drain cycles (target flush rejected, metadata read
     * failed, …) tolerated before the consumer parks the project in ERROR with an
     * APPLY_STRIKE_OUT. SQL Server reads typed columns straight off the CT ResultSet —
     * unlike Oracle there is no per-row SQL_REDO parse stage — so the apply-side counter
     * is the ONLY strike-out that can ever fire here. Mirrors PgLogicalCdcConsumer (F-10)
     * and LogMinerCdcConsumer (F-12): without it a target that rejects every flush (e.g.
     * a column the source added while the project was stopped — the classifier files that
     * SQLException as TRANSIENT) spins forever, never parking and never advancing.
     */
    static final int MAX_APPLY_FAILURES = 5;

    /**
     * P1-6: even an idle drain (maxLsn unmoved) re-runs capture-instance discovery at
     * least this often. A CI the DBA enables mid-flight has no DML yet, so maxLsn never
     * moves for it and the old early return kept the consumer blind until the first row
     * changed — contradicting the probe's "ready" verdict. Throttled because the 1s idle
     * poll must not hammer cdc.change_tables every cycle.
     */
    static final long DISCOVERY_REFRESH_MS = 60_000L;

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

    private final CdcTargetTransactionFactory targetTransactions;
    /**
     * Targeted repair for a deleted target table; null when the engine was built without
     * it (tests, hand-built consumers) — then a missing table parks instead of looping.
     */
    private final CdcTargetRepairService targetRepair;
    private final BindingSupplier supplier = new BindingSupplier();

    /** P1-6: discovery throttle + the CI names this consumer has already logged. */
    private long lastDiscoveryAtMs;
    private List<CaptureInstance> cachedCaptureInstances = List.of();
    private final Set<String> knownCaptureInstances = new HashSet<>();
    /**
     * Capture instance → the missing-column description last warned about. A CI whose
     * change table lacks a binding column is skipped every cycle; without this the same
     * WARN would repeat on every poll, and without the per-CI key two unhealthy instances
     * would flap each other's single-throttle slot.
     */
    /** #4: throttle for the "CDC disabled at DB level" warning (see drain()). */
    private long lastCdcOffWarnMs;
    /** Throttle for the "no capture instance for any selected table" warning. */
    private long lastNoInstanceWarnMs;

    private volatile boolean running = true;

    /**
     * Last source-catalog scan for tables created after auto-sync was enabled. A table
     * without a capture instance produces no change rows at all, so the catalog is asked
     * once a minute instead of waiting for a row that will never come.
     */
    private volatile long lastAutoSyncCheckMs;
    private volatile Connection currentSource;
    /**
     * The change-function statement currently executing. {@code requestStop()} cancels it so
     * a stop cannot sit behind a slow/hung scan of a large LSN window.
     */
    private volatile Statement activeStatement;
    /** One warning for a broken lag lookup, not one per scanned window. */
    private volatile boolean lagLookupWarned;
    /**
     * Newest transaction LSN folded in this drain window, across capture instances. One lag
     * lookup per window instead of one per scanned table: a 700-table project would otherwise
     * pay 700 round trips per cycle for a panel number.
     */
    private volatile String lastScannedTxLsn;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    /** F-13: consecutive failed drain cycles; reset to 0 on every successful flush. */
    private final AtomicInteger applyFailures = new AtomicInteger();
    /**
     * Whether THIS cycle failed while applying to the target. Set only around a target
     * flush (see {@link #drain}), so a source-side read failure mid-window retries with
     * backoff instead of accumulating towards APPLY_STRIKE_OUT.
     */
    private boolean applyFailedThisCycle;
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    /** Per-table aggregated change-log flusher (10s windows, final flush on stop). */
    private final CdcChangeWindowLogger windowLogger;

    /** LOB spill store for buffered rows, built on first use; null when switched off. */
    private final LazyLobSpillStore lobSpillStore = new LazyLobSpillStore();
    /** Owns the files of the window currently folded; reset when the window ends. */
    private final CdcSpillerHolder spillers = new CdcSpillerHolder();

    public SqlServerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                                SyncContext ctx, CdcPositionStore positionStore,
                                CdcBaselineService baselineService, SyncTaskStore taskStore,
                                SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                                CdcRuntimeStats stats, SyncProperties properties) {
        this(project, lock, ctx, positionStore, baselineService, taskStore, stateWriter,
                dataSourceManager, stats, properties,
                new CdcTargetTransactionFactory(stats, properties.getQueryTimeoutSeconds()));
    }

    public SqlServerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                                SyncContext ctx, CdcPositionStore positionStore,
                                CdcBaselineService baselineService, SyncTaskStore taskStore,
                                SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                                CdcRuntimeStats stats, SyncProperties properties,
                                CdcTargetTransactionFactory targetTransactions) {
        this(project, lock, ctx, positionStore, baselineService, taskStore, stateWriter,
                dataSourceManager, stats, properties, targetTransactions, null);
    }

    public SqlServerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                                SyncContext ctx, CdcPositionStore positionStore,
                                CdcBaselineService baselineService, SyncTaskStore taskStore,
                                SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                                CdcRuntimeStats stats, SyncProperties properties,
                                CdcTargetTransactionFactory targetTransactions,
                                CdcTargetRepairService targetRepair) {
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
        this.targetTransactions = targetTransactions;
        this.targetRepair = targetRepair;
        this.windowLogger = new CdcChangeWindowLogger(project.getId(), stats, stateWriter,
                taskStore, properties.getCdc().getChangeWindowMs());
    }

    @Override
    public void run() {
        long projectId = project.getId();
        // Pair the stop line with a start line so restarts do not read as a crash loop.
        try {
            stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                    ChangeType.INFO, "SQLServer consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the SQLServer start log for project {}: {}",
                    projectId, e.getMessage());
        }
        try {
            // Inside the try so a failed task-store write still reaches the finally that
            // releases the daemon lease.
            taskStore.markCdcRunning(projectId);
            if (targetRepair != null) {
                targetRepair.resetAttempts(projectId);
            }
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
            long idlePollMs = CdcReconnectBackoff.effectiveIdlePollMs(
                    ctx.getConfig().getCdcIdlePollMs(),
                    properties.getCdc().getMssql().getIdlePollMs());
            windowLogger.start();
            // The throughput window covers the whole run, not one poll cycle: the panel
            // divides the cumulative rows applied by the elapsed window. Stamping it inside
            // the loop reported 0.0 right after every poll and an ever-growing rate once a
            // cycle took longer than a second (CdcRuntimeStats.eventsPerSecond's floor).
            stats.markStarted();
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
                // Between windows is the only safe point: the targeted load opens its own
                // source connection and writes the target.
                reconcileAutoSync(projectId);
                // F-13 counts APPLY failures only. drain marks this when a target flush
                // fails; a source read/stream failure mid-window leaves it false, so it
                // retries with backoff (the same rule as "source unreachable"). Counting
                // every drain exception parked the project after ~30 s of network trouble.
                applyFailedThisCycle = false;
                int rowsDrained;
                try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                    currentSource = conn;
                    stats.setConnected(true);
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    DrainResult result = drain(conn, fromLsn);
                    rowsDrained = result.rows;
                    if (result.nextLsn != null) {
                        fromLsn = result.nextLsn;
                    }
                } catch (Exception e) {
                    // Idempotent at-least-once: re-reading an interval is safe. But a flush
                    // that failed late in the window already persisted the LSN of the
                    // transactions before it, and re-scanning from the pre-cycle anchor would
                    // re-apply those (real target writes, plus a "refusing to rewind" WARN per
                    // transaction). Anchor on the newest persisted LSN: it is the first
                    // transaction that was NOT committed, so nothing below it is lost.
                    if (e instanceof LockLostException) {
                        // 租约被接管：另一节点拥有位点并会重放这笔事务；安静退出，
                        // 不把它记成目标写失败，避免在对端写 ERROR 帽。
                        stats.setConnected(false);
                        break;
                    }
                    fromLsn = newestPersistedLsn(projectId, fromLsn);
                    if (applyFailedThisCycle) {
                        applyFailures.incrementAndGet();
                    }
                    lastFailure.compareAndSet(null, e);
                    rowsDrained = 0;
                    // The poll loop opens a connection per cycle, so "connected" cannot be
                    // tied to the connection's lifetime: between cycles there is none, and the
                    // panel would show 已断开 for almost the whole run of a healthy consumer.
                    // The status means "the cycle's read path works", so only a failed cycle
                    // drops it; a healthy cycle leaves the panel green until the next poll.
                    stats.setConnected(false);
                } finally {
                    closeQuietly(currentSource);
                    activeStatement = null;
                    currentSource = null;
                    // Cycle boundary: the position throttle may still hold the newest
                    // committed LSN; write it before the connection goes away.
                    flushPosition(projectId);
                }
                if (!running) break;
                Throwable failure = lastFailure.get();
                if (failure == null) {
                    // F-13 counts CONSECUTIVE failed cycles. A cycle that completed
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
                // 目标表被删:重建并重灌这一张(或这几张)表,位点不动。修复成功就立刻
                // 重放同一笔事务;修复没成功才停机,并把修复给出的原因带进 ERROR。
                CdcTargetRepairService.Outcome repair = CdcTargetRepairGate.attempt(
                        targetRepair, ctx, failure, stats);
                if (repair != null) {
                    if (repair.repaired()) {
                        applyFailures.set(0);
                        stats.setLastError(null);
                        log.warn("SQLServer consumer for project {}: {}; replaying from the"
                                + " same LSN", projectId, repair.detail());
                        continue;
                    }
                    fatalStop(projectId, new SQLException("targeted repair "
                            + repair.status() + ": " + repair.detail()),
                            CdcFailureClassifier.Kind.TARGET_TABLE_MISSING);
                    break;
                }
                if (kind != CdcFailureClassifier.Kind.TRANSIENT || applyStrikeOut()) {
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
                // completion line belongs at DEBUG: idle polling runs every 1000 ms by
                // default, so logging it at INFO flooded the log of every healthy project.
                if (failure == null) {
                    stats.setLastError(null);
                    log.debug("SQLServer cycle for project {} drained; next cycle in {} ms",
                            projectId, delay);
                } else {
                    String detail = CdcFailureClassifier.describe(failure);
                    stats.setLastError(detail);
                    log.warn("SQLServer cycle for project {} failed ({}); next cycle in {} ms",
                            projectId, detail, delay);
                }
                if (!CdcWaits.sleepWhileRunning(() -> running, delay)) break;
            }
        } catch (RuntimeException | Error e) {
            if (e instanceof LockLostException) {
                // R1: the baseline lost the lease to another node; that node owns the
                // position row and will redo the baseline. Quiet exit, never ERROR.
                log.info("SQLServer consumer for project {} stopped: lease lost during the"
                        + " baseline ({}); not parking it in ERROR", projectId, e.toString());
            } else if (!running || Thread.currentThread().isInterrupted()) {
                log.info("SQLServer consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else if (CdcFailureClassifier.isTransient(e)) {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);
                log.warn("SQLServer consumer for project {} hit a transient failure;"
                        + " supervision will retry: {}", projectId, detail);
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
            activeStatement = null;
            currentSource = null;
            windowLogger.close();
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

    static final class DrainResult {
        int rows;
        String nextLsn;
    }

    /** A SQL Server LSN is 10 bytes — 20 hex digits — rendered as "0x" + lowercase hex. */
    private static final int LSN_HEX_DIGITS = 20;

    /**
     * Normalises a persisted watermark to the canonical LSN text form used by every
     * query in this class: {@code 0x} + lowercase hex (what
     * {@code CONVERT(varchar(32), lsn, 1)} returns). v1.7.0 persisted
     * {@code __$start_lsn} straight from the raw binary column ({@code hex(bytes)}),
     * i.e. UPPERCASE with no {@code 0x} prefix. Nothing else normalises it, and the
     * watermark is rebound with {@code CONVERT(binary(10), ?, 1)} — style 1 requires
     * exactly the canonical shape, so a legacy row failed with Msg 8114 ("Error
     * converting data type varchar to varbinary") on every cycle. The classifier sees
     * that as TRANSIENT, so the consumer retried until APPLY_STRIKE_OUT parked the
     * project for good.
     *
     * <p>Unknown shapes are returned untouched: a watermark this code cannot recognise
     * must still reach SQL Server and fail visibly instead of being silently rewritten.
     */
    static String canonicalLsn(String lsn) {
        if (lsn == null) {
            return null;
        }
        String s = lsn.trim();
        int start = s.startsWith("0x") || s.startsWith("0X") ? 2 : 0;
        if (s.length() - start != LSN_HEX_DIGITS) {
            return lsn;
        }
        for (int i = start; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return lsn;
            }
        }
        return "0x" + s.substring(start).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the source's current capture LSN sits BEHIND the stored watermark: the source
     * database was restored (or rolled back) to an earlier point in time, or the project now
     * points at a different server whose capture LSNs are lower.
     *
     * <p>Nothing above the watermark exists in that source's change tables, and
     * {@code fn_cdc_get_all_changes_*} rejects the inverted range {@code (from, max]} with
     * Msg 313 on every attempt. The failure is not raised around {@code flushTransaction},
     * so {@code applyFailedThisCycle} stays false, the cycle is classified TRANSIENT, and the
     * consumer used to retry the same impossible range forever — never advancing, never
     * parking, never saying why. Retrying cannot recover the missing window, so the caller
     * parks the project with the PURGED ("re-baseline from a snapshot") remedy instead.
     *
     * <p>Both sides must be canonical LSNs ({@link #canonicalLsn}: {@code 0x} + 20 lowercase
     * hex — fixed width, so string order IS LSN order). An unrecognised stored shape yields
     * "no verdict": it must still reach SQL Server and fail visibly (Msg 8114) rather than be
     * misread as a backwards timeline and park a healthy project.
     */
    /**
     * SQL Server rejects a change-function call for two different reasons with two different
     * errors: error 22054 ("invalid LSN for the capture instance") on some builds, and the
     * famously misleading error 313 ("An insufficient number of arguments were supplied…")
     * on others — SQL Server 2022 CU27 answers 313 when @from_lsn sits BELOW the instance's
     * min_lsn. Both mean the same thing here and must take the same clamp/purge/empty path;
     * gating on 22054 alone left a new capture instance retrying 313 forever.
     */
    static boolean lsnRejectedFault(SQLException e) {
        if (e == null) {
            return false;
        }
        if (e.getErrorCode() == 22054) {
            return true;
        }
        if (e.getErrorCode() != 313) {
            return false;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT)
                .contains("insufficient number of arguments");
    }

    static boolean watermarkBeyondSource(String fromLsn, String maxLsn) {
        String from = canonicalLsn(fromLsn);
        String max = canonicalLsn(maxLsn);
        if (!isCanonicalLsn(from) || !isCanonicalLsn(max)) {
            return false;
        }
        return max.compareTo(from) < 0;
    }

    private static boolean isCanonicalLsn(String lsn) {
        if (lsn == null || lsn.length() != 2 + LSN_HEX_DIGITS) {
            return false;
        }
        if (lsn.charAt(0) != '0' || lsn.charAt(1) != 'x') {
            return false;
        }
        for (int i = 2; i < lsn.length(); i++) {
            char c = lsn.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
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
    DrainResult drain(Connection conn, String fromLsn) throws Exception {
        try {
            return drainWindow(conn, canonicalLsn(fromLsn));
        } finally {
            // Rows a stop left unflushed are re-read from the capture tables next start;
            // their spilled files have no reader left.
            resetSpiller();
        }
    }

    private DrainResult drainWindow(Connection conn, String fromLsn) throws Exception {
        long projectId = project.getId();
        DrainResult out = new DrainResult();
        lastScannedTxLsn = null;
        String maxLsn = readMaxLsn(conn);
        if (maxLsn == null) {
            // #4: a NULL here used to be silent, so an idle consumer spun forever with no
            // signal — the same "silent stall" class as 22054. Warn instead, throttled to
            // once per DISCOVERY_REFRESH_MS so the 1s idle poll cannot spam, and keep
            // polling: do NOT park — the stream resumes automatically.
            //
            // The cause is NOT always "CDC disabled": fn_cdc_get_max_lsn() reads
            // cdc.lsn_time_mapping, which stays empty until the capture job processes its
            // first transaction (verified on SQL Server 2022). On a freshly enabled but
            // so-far quiet database the old wording sent DBAs to sp_cdc_enable_db when the
            // real clue was a stopped SQL Server Agent. Ask the database-level flag.
            long now = System.currentTimeMillis();
            if (now - lastCdcOffWarnMs >= DISCOVERY_REFRESH_MS) {
                lastCdcOffWarnMs = now;
                Boolean dbEnabled = readCdcDbEnabled(conn);
                if (Boolean.FALSE.equals(dbEnabled)) {
                    log.warn("SQLServer CDC for project {} is disabled at the database level"
                            + " (sys.fn_cdc_get_max_lsn() returned NULL, is_cdc_enabled=0);"
                            + " no changes will be captured until a DBA re-enables CDC. The"
                            + " consumer keeps polling and resumes automatically once it is"
                            + " back on.", projectId);
                } else if (Boolean.TRUE.equals(dbEnabled)) {
                    log.warn("SQLServer CDC for project {} has captured nothing yet"
                            + " (sys.fn_cdc_get_max_lsn() returned NULL while"
                            + " is_cdc_enabled=1) — the capture job has not processed a"
                            + " transaction; make sure the SQL Server Agent is running. The"
                            + " consumer keeps polling and resumes automatically at the first"
                            + " captured change.", projectId);
                } else {
                    log.warn("SQLServer CDC for project {} sees sys.fn_cdc_get_max_lsn() ="
                            + " NULL — either CDC is disabled at the database level or"
                            + " nothing has been captured yet; check"
                            + " sys.databases.is_cdc_enabled and the SQL Server Agent. The"
                            + " consumer keeps polling and resumes automatically.", projectId);
                }
            }
            return out;
        }
        if (watermarkBeyondSource(fromLsn, maxLsn)) {
            // The timeline moved backwards under us. Say so loudly first: the message names
            // both LSNs (a log-only fix is useless if the DBA cannot see what the source now
            // reports), then hand the classifier the "SQLServer CDC purge" sentinel → PURGED
            // → the consumer parks with the re-baseline remedy instead of hot-looping.
            log.error("SQLServer CDC for project {} is BEHIND its stored watermark: the source"
                    + " now reports fn_cdc_get_max_lsn() = {} while the project already"
                    + " applied up to {} — the source was restored/rolled back to an earlier"
                    + " point in time, or the project now points at a different source. Changes"
                    + " above the watermark no longer exist in this source's change tables.",
                    projectId, maxLsn, fromLsn);
            throw new SQLException("SQLServer CDC purge: the source's current capture LSN "
                    + maxLsn + " is BEHIND the stored watermark " + fromLsn
                    + " — the source database was restored or rolled back to an earlier point"
                    + " in time (or the project now points at a different source). The changes"
                    + " in between are gone; re-baseline the project from a fresh snapshot.");
        }
        if (maxLsn.equalsIgnoreCase(fromLsn)) {
            // P1-6: the idle early return must not starve discovery — re-read
            // cdc.change_tables on a throttled cadence so a newly enabled capture
            // instance is seen (and logged) now, not at its first DML. Refresh only:
            // the (fromLsn, maxLsn] interval is empty by definition here.
            captureInstances(conn);
            return out; // nothing new
        }
        List<CaptureInstance> instances = captureInstances(conn);
        if (instances.isEmpty()) {
            // Nothing will ever show up past fromLsn without DBA action: do NOT advance the
            // watermark — that would hide the gap. Advance to maxLsn only when at least one
            // tracked instance exists, so the next poll starts from the current position
            // when the DBA enables CDC mid-stream. This is a configuration state, not a
            // skipped change, so it must not touch the skippedUnsupported counter (which
            // used to climb by one on every poll while nothing was being skipped at all).
            warnNoCaptureInstances();
            return out;
        }
        // P0-6: fold rows per transaction in SOURCE ORDER (the SQL guarantees
        // ORDER BY __$start_lsn, __$seqval): same-PK last-op-wins, op=3/op=4 pairs
        // delete the old PK when an UPDATE moved it, and transactions flush in
        // ascending txLsn order so advanceLsn stays monotonic across capture instances.
        SqlServerTxFold fold = new SqlServerTxFold(
                properties.getCdc().getMaxTransactionRows(),
                properties.getCdc().getMaxTransactionBytes());
        // How far this cycle may consume the window: a scan that stopped at its row budget
        // reports its last complete transaction, and the next cycle resumes there. The
        // earliest stop across capture instances wins (re-reading another instance's already
        // applied interval is idempotent, skipping an unscanned one is loss).
        String boundedTo = maxLsn;
        for (CaptureInstance ci : instances) {
            // A stop landing between CIs makes the shared fold partial just as surely as one
            // landing mid-ResultSet: a transaction spanning tables only a later CI scans
            // would be missing rows. Do not start another scan, and (checked again below)
            // do not flush anything the scan did not finish.
            if (!running || Thread.currentThread().isInterrupted()) {
                return out;
            }
            // from_lsn is INCLUSIVE — pass fn_cdc_increment_lsn to skip the watermark
            // transaction we already applied on the previous cycle. The persisted value
            // remains fromLsn; only the SQL interval shifts.
            String scanEnd = null;
            try {
                scanEnd = scanCi(conn, ci, changesSql(ci.captureInstance), fromLsn, maxLsn,
                        fold, out);
            } catch (SQLException e) {
                // SQL Server error 22054 == "an invalid LSN was specified for the capture
                // instance": the @from_lsn we passed is BELOW this CI's valid range. It
                // does NOT mean "no rows in the window" — an empty window returns zero
                // rows, never an error. #2: DETECT which case fired instead of skipping
                // the CI (a persistent 22054 skip is a silent stall) or blind-clamping
                // (which would silently drop purged changes):
                //   ① the CI was enabled/re-created mid-flight → its start_lsn sits
                //      ABOVE our watermark. Clamping @from_lsn to min_lsn and rescanning
                //      INCLUSIVE drops no captured row and self-heals — but it proves
                //      only that THIS instance captured nothing earlier, not that the
                //      table had no changes: if the instance was created after the
                //      baseline, the interval (watermark, clamp point) was never
                //      captured at all (counted + warned, operator must verify);
                //   ② the cleanup job purged an ESTABLISHED CI past our watermark
                //      (start_lsn ≤ watermark < min_lsn) → the changes in
                //      [watermark, min_lsn) are physically gone; clamping would skip
                //      them silently, so throw a PURGED-classified SQLException and
                //      park the project for a re-baseline instead (user ruling).
                // If start_lsn is unknown the two cases are indistinguishable → park;
                // never risk the silent data loss of a blind clamp.
                // Anything else — permission, network, column drift — aborts the whole
                // drain so we retry instead of silently skipping this ci.
                if (lsnRejectedFault(e)) {
                    String minLsn = readMinLsn(conn, ci.captureInstance);
                    String fromCanonical = canonicalLsn(fromLsn);
                    String startCanonical = canonicalLsn(ci.startLsn);
                    if (startCanonical != null && fromCanonical != null
                            && startCanonical.compareTo(fromCanonical) > 0) {
                        // ① new CI. min_lsn is NULL while its change table holds no rows
                        // yet — start_lsn is then the lowest LSN the function accepts.
                        String clampTo = canonicalLsn(minLsn != null ? minLsn : ci.startLsn);
                        String maxCanonical = canonicalLsn(maxLsn);
                        if (clampTo.compareTo(maxCanonical) > 0) {
                            // The capture job has not yet processed this CI's enable
                            // transaction, so fn_cdc_get_max_lsn() still sits BELOW its
                            // first readable LSN (min_lsn is NULL while the change table is
                            // empty). Binding @from_lsn > @to_lsn is an invalid range —
                            // SQL Server rejects it with error 313 — and there is nothing
                            // past the watermark for this CI to read anyway. Skip it this
                            // cycle; the watermark still advances, and the next cycle picks
                            // the CI up once the capture job catches up.
                            log.debug("SQLServer CDC: capture instance {} starts at {} above"
                                    + " max_lsn {} — nothing captured for it yet; skipping"
                                    + " it this cycle", ci.captureInstance, clampTo, maxLsn);
                            continue;
                        }
                        // The clamp itself loses nothing, but that is NOT the same as "no
                        // changes were lost": a CI created or re-created AFTER the baseline
                        // means the interval between the watermark and this point was never
                        // captured by ANY instance — the old claim was false reassurance.
                        // Without CI history the two causes are indistinguishable, so keep
                        // self-healing and say what is actually known.
                        stats.addUncapturedGap();
                        log.warn("SQLServer CDC: capture instance {} starts at {} above the"
                                + " stored watermark {} (enabled or re-created mid-flight);"
                                + " clamping from_lsn to {} and rescanning inclusively."
                                + " The interval ({}, {}) was never captured for this"
                                + " instance — if it was created or re-created after the"
                                + " baseline, changes in that interval are missing on the"
                                + " target; verify them against the source and re-baseline"
                                + " if needed", ci.captureInstance, ci.startLsn, fromLsn,
                                clampTo, fromLsn, clampTo);
                        scanEnd = scanCi(conn, ci, changesSqlInclusive(ci.captureInstance),
                                clampTo, maxLsn, fold, out);
                        boundedTo = earlierLsn(boundedTo, scanEnd);
                        continue;
                    }
                    if (minLsn == null) {
                        // An empty change table has no lower bound, so EVERY call is
                        // rejected — and there is nothing to read anyway. Skipping it is
                        // not a gap: the moment the capture job writes its first row the
                        // next cycle scans it normally.
                        log.debug("SQLServer CDC: capture instance {} has no captured rows"
                                + " yet (min_lsn is NULL); skipping it this cycle",
                                ci.captureInstance);
                        continue;
                    }
                    if (minLsn != null && fromCanonical != null
                            && canonicalLsn(minLsn).compareTo(fromCanonical) <= 0) {
                        // The rejection names neither a clamp point nor a purge; retrying
                        // with the same bindings cannot help, but parking on a guess is
                        // worse. Propagate and let the caller classify.
                        throw e;
                    }
                    // ② purged past the watermark — unrecoverable; park as PURGED. No
                    // cause chain: describe() reports the ROOT message and the remedy
                    // text below must be what reaches sync_task.last_sync_result.
                    log.warn("SQLServer CDC: capture instance {} rejected watermark {}"
                            + " (error 22054) and its min_lsn {} is above it — the cleanup"
                            + " job purged the changes in between; parking the project",
                            ci.captureInstance, fromLsn, minLsn, e);
                    throw new SQLException("SQLServer CDC purge: capture instance "
                            + ci.captureInstance + " has min_lsn " + minLsn + " above the"
                            + " stored watermark " + fromLsn + " — the changes in between"
                            + " were removed by the CDC cleanup job and cannot be"
                            + " recovered; re-baseline the project from a fresh snapshot");
                }
                // Propagate — the caller leaves fromLsn untouched and retries.
                throw e;
            }
            boundedTo = earlierLsn(boundedTo, scanEnd);
        }
        if (lastScannedTxLsn != null) {
            recordTxLag(conn, lastScannedTxLsn);
            lastScannedTxLsn = null;
        }
        // The scan phase finished while running on every CI — only now does the fold
        // contain COMPLETE transactions. If a stop/interrupt cut a scan short, abandon the
        // whole window: the fold may hold a multi-table transaction missing tables, or an
        // UPDATE whose op=4 never arrived (the fold would DELETE a row the source keeps).
        // Committing it and advancing its txLsn would skip the missing rows forever after
        // restart. The window re-reads on the next start and lands via idempotent upserts.
        if (!running || Thread.currentThread().isInterrupted()) {
            return out;
        }
        // Flush per-transaction in ascending txLsn order (fold.ordered() is a TreeMap).
        for (Map.Entry<String, Map<CdcTableBinding, SqlServerTxFold.Buffer>> e
                : fold.ordered().entrySet()) {
            // A stop between flushes leaves later, unflushed transactions for the restart;
            // already-landed transactions advanced their own LSNs in flushTransaction.
            if (!running || Thread.currentThread().isInterrupted()) {
                break;
            }
            // boundedTo is the earliest point at which ANY capture instance stopped, so a
            // transaction above it may belong to an interval another CI has not scanned yet.
            // Flushing it would persist a later LSN that the truncated CI's remaining rows
            // sit under — after a restart they would be skipped silently. Leave the tail
            // for the next cycle (the scan re-reads it; at-least-once).
            if (boundedTo != null && e.getKey().compareToIgnoreCase(boundedTo) > 0) {
                break;
            }
            try {
                flushTransaction(projectId, e.getKey(), e.getValue());
            } catch (Exception ex) {
                applyFailedThisCycle = true;            // 写目标端失败才计入熔断
                throw ex;
            }
        }
        // #3: reaching here means the loop completed without aborting — every tracked
        // CI was scanned over (fromLsn, maxLsn] (normally, or from a #2 clamp point).
        // Rows were either folded or deliberately skipped as unsupported, so the window
        // is fully consumed and the watermark advances to maxLsn EVEN WHEN NOTHING
        // FOLDED. The old per-row highWater only advanced on a tracked row: a project
        // whose tracked tables are quiet while untracked tables churn kept fromLsn
        // pinned, re-running full discovery over a monotonically growing window every
        // cycle until aged-out logs tripped 22054. Guarded by running: an interrupted
        // scan did not finish the window and must not advance it.
        //
        // P1-8: persist it too. Previously only flushTransaction wrote the position, so
        // a window that folded nothing (every tracked table unsupported/no PK, or quiet
        // while untracked tables churned) moved the in-memory watermark only. A restart
        // then resumed at the old persisted LSN; by the time CDC cleanup (72h default)
        // had purged the interval, the restart met 22054 with start_lsn <= watermark and
        // parked the project as PURGED — a false data-loss park with nothing lost.
        // advanceLsn is monotonic and a no-op when a flush in this window already wrote a
        // later LSN, so data-first order is preserved (flushTransaction runs above).
        if (running && !Thread.currentThread().isInterrupted()) {
            // Not necessarily maxLsn: a scan that stopped at its row budget leaves the
            // interval above its last complete transaction for the next cycle. Persisting
            // maxLsn would silently skip it after a restart.
            out.nextLsn = boundedTo;
            positionStore.advanceLsn(projectId, boundedTo);
        }
        return out;
    }

    /** The earlier of two canonical LSNs; {@code null} means "not bounded". */
    private static String earlierLsn(String a, String b) {
        if (b == null) {
            return a;
        }
        if (a == null) {
            return b;
        }
        return a.compareToIgnoreCase(b) <= 0 ? a : b;
    }

    /**
     * Scan one capture instance over the window its SQL defines — (fromBind, toLsn]
     * for {@link #changesSql}, [fromBind, toLsn] for the inclusive #2 clamp variant —
     * folding every row into {@code fold}. Shared by the normal per-cycle pull and the
     * clamp rescan so both paths fold identically.
     *
     * @return the LSN the next cycle must resume from when the scan stopped at a transaction
     *     boundary before reaching {@code toLsn} (several million small transactions in one
     *     window otherwise all sit in the fold before anything is flushed), or {@code null}
     *     when the whole window was read. The bound is transaction-aligned on purpose: a
     *     transaction is never left half-folded, because the fold's net-effect rules depend
     *     on seeing all of it.
     */
    private String scanCi(Connection conn, CaptureInstance ci, String sql, String fromBind,
                          String toLsn, SqlServerTxFold fold, DrainResult out)
            throws SQLException {
        long maxRows = properties.getCdc().getMssql().getMaxRowsPerCycle();
        long rows = 0;
        String currentTx = null;
        // A budget cut returns from here; the lag sample still runs for the transaction
        // actually folded, so the helper is called after the result set is closed.
        String resumeAt = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fromBind);
            ps.setString(2, toLsn);
            activeStatement = ps;
            try (ResultSet rs = ps.executeQuery()) {
                // Cache the real column set of the CT row ONCE per ci, on the first row
                // (an empty window must not cost a metadata read) — RowReader consults it
                // to distinguish "not captured" from genuine errors.
                Set<String> availableCols = null;
                CdcTableBinding binding = null;
                RowReader reader = null;
                RowReader keysOnlyReader = null;
                while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                    if (availableCols == null) {
                        // The binding and the reader are per-window constants: resolving
                        // them once keeps the per-row cost to the actual read (the supplier
                        // is consulted again on the next scan, so a DDL invalidated between
                        // cycles still lands on a fresh binding).
                        binding = supplier.binding(ci);
                        availableCols = availableColumns(rs);
                        List<String> missingCols =
                                missingBindingColumns(binding, availableCols);
                        if (!missingCols.isEmpty()) {
                            // @captured_column_list captured only a subset of the table (or
                            // the column was added after the instance was created). The
                            // applier binds EVERY binding column and JdbcRowMapper turns a
                            // missing map key into an explicit NULL, so folding these rows
                            // would overwrite the target's values — silently when the
                            // column is nullable. Skipping the window and advancing the
                            // watermark turned that into permanent blindness for the table:
                            // every later change was discarded with a green-looking stream.
                            // The source's capture instance must be rebuilt; park instead.
                            throw new IllegalStateException(CdcSchemaDrift.MARKER
                                    + ": SQL Server capture instance " + ci.captureInstance
                                    + " of " + ci.schema + "." + ci.table
                                    + " does not capture column(s) "
                                    + String.join(", ", missingCols)
                                    + "; disable and re-enable CDC for the table with a"
                                    + " capture instance that includes every column (for"
                                    + " example @captured_column_list='*'), then restart the"
                                    + " project and rebuild the baseline to refill the gap");
                        }
                        reader = RowReader.of(rs, binding, availableCols);
                        // Reads only the primary key. Used for the delete and update-before
                        // images, whose payload never reaches the target. An absent binding
                        // makes both readers null: the row-level check below counts the whole
                        // table as unsupported instead of dereferencing it here.
                        keysOnlyReader = binding == null || binding.pkNames().isEmpty() ? reader
                                : RowReader.of(rs, binding, availableCols,
                                        new HashSet<>(binding.pkNames()));
                    }
                    // Column 1 is projected as CONVERT(varchar(32), __$start_lsn, 1) by
                    // changesSql/changesSqlInclusive. Do NOT select the raw binary column:
                    // mssql-jdbc renders a binary(10) through getString as UPPERCASE hex
                    // WITHOUT the 0x prefix (DDC.convertBytesToObject), and that value is
                    // persisted by advanceLsn and later rebound with
                    // CONVERT(binary(10), ?, 1) — style 1 REQUIRES the 0x prefix, so Msg
                    // 8114 "Error converting data type varchar to varbinary" made every
                    // cycle after a restart fail until APPLY_STRIKE_OUT parked the project.
                    // Verified on SQL Server 2022 with mssql-jdbc 12.10.2.
                    String startLsn = rs.getString(1);
                    if (!startLsn.equals(currentTx)) {
                        if (maxRows > 0 && rows >= maxRows) {
                            // A new transaction and the budget is spent: stop here. The fold
                            // holds whole transactions only, so everything above `currentTx`
                            // is re-read next cycle — the row just fetched is dropped, not
                            // half-folded.
                            resumeAt = currentTx;
                            break;
                        }
                        currentTx = startLsn;
                    }
                    int op = rs.getInt(2);
                    if (reader == null) {
                        stats.addUnsupportedTable(ci.schema + "." + ci.table);
                        continue;
                    }
                    Map<String, Object> row = readerFor(op, reader, keysOnlyReader).read(rs);
                    if (op == 2 || op == 4) {
                        // Insert / update-after images reach the target whole. Delete (1) and
                        // update-before (3) images only supply primary keys to the fold, so
                        // spilling their payload would write files nobody ever reads — and a
                        // bulk delete of large rows would fill the spill directory doing it.
                        row = spiller().spill(binding, row);
                    }
                    fold.accept(startLsn, binding, op, row);
                    out.rows++;
                    rows++;
                }
            }
        }
        if (currentTx != null && (lastScannedTxLsn == null
                || currentTx.compareToIgnoreCase(lastScannedTxLsn) > 0)) {
            lastScannedTxLsn = currentTx;
        }
        if (resumeAt != null) {
            return resumeAt;
        }
        return null;   // the whole window was read
    }

    /**
     * Samples how far behind the newest folded transaction was, once per scanned window.
     * Failure is ignored on purpose: a panel metric must never stop replication, and the
     * worst case is the previous sample staying on screen.
     */
    private void recordTxLag(Connection conn, String commitLsn) {
        if (commitLsn == null || commitLsn.isBlank()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(TX_LAG_SQL)) {
            ps.setString(1, commitLsn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lagMs = rs.getLong(1);
                    if (!rs.wasNull() && lagMs >= 0) {
                        stats.touchEvent(System.currentTimeMillis() - lagMs);
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            // Hand-built contexts and odd drivers may return no handle at all; a panel
            // metric must never take the drain down with it. Warn once so a real permission
            // or SQL problem does not stay invisible at DEBUG.
            if (!lagLookupWarned) {
                lagLookupWarned = true;
                log.warn("SQLServer CDC lag lookup for project {} failed ({}); the lag panel"
                        + " keeps its previous sample", project.getId(), e.toString());
            } else {
                log.debug("SQLServer CDC lag lookup for project {} failed: {}",
                        project.getId(), e.toString());
            }
        }
    }

    /**
     * The reader a row image needs: delete (1) and update-before (3) images only supply
     * primary keys to {@link SqlServerTxFold}, while insert (2) and update-after (4) images
     * are applied whole.
     */
    static RowReader readerFor(int op, RowReader full, RowReader keysOnly) {
        return op == 1 || op == 3 ? keysOnly : full;
    }

    /**
     * The change-pull SQL for one capture instance (P0-6): {@code 'all update old'}
     * makes the function emit op=3 update-before images (the fold needs them to delete
     * moved PKs), and the explicit {@code ORDER BY __$start_lsn, __$seqval} is the
     * contract the fold's net-effect semantics rely on — the function's default order
     * is documented behavior, the ORDER BY is a guarantee.
     */
    static String changesSql(String captureInstance) {
        return "SELECT CONVERT(varchar(32), __$start_lsn, 1), __$operation, *"
                + " FROM " + captureFunction(captureInstance)
                + "((SELECT sys.fn_cdc_increment_lsn(CONVERT(binary(10), ?, 1))),"
                + " CONVERT(binary(10), ?, 1),'all update old')"
                + " ORDER BY __$start_lsn, __$seqval";
    }

    /**
     * #2 clamp variant of {@link #changesSql}: binds @from_lsn RAW — no
     * fn_cdc_increment_lsn — so a change sitting exactly AT the clamp point is
     * included. The normal pull increments to skip the watermark transaction already
     * applied last cycle; the clamp point (min_lsn, or a new CI's start_lsn when its
     * change table is still empty) was never applied, so incrementing there could skip
     * the boundary row. Replaying a row is safe (idempotent upserts); skipping is loss.
     */
    static String changesSqlInclusive(String captureInstance) {
        return "SELECT CONVERT(varchar(32), __$start_lsn, 1), __$operation, *"
                + " FROM " + captureFunction(captureInstance)
                + "(CONVERT(binary(10), ?, 1), CONVERT(binary(10), ?, 1),'all update old')"
                + " ORDER BY __$start_lsn, __$seqval";
    }

    /**
     * The lag card needs the source time of the newest change. The change table only carries
     * {@code __$start_lsn}; {@code cdc.lsn_time_mapping} maps that commit LSN to its commit
     * time, and doing the subtraction server-side ({@code SYSUTCDATETIME()}) avoids the
     * driver's local-timezone reinterpretation of the stored UTC datetime. This stayed at
     * zero while the consumer passed {@code System.currentTimeMillis()} to
     * {@code touchEvent} — a number that can never be late against itself.
     *
     * <p>Deliberately a separate PK lookup per scanned window rather than a join in
     * {@link #changesSql}: joining the mapping table into the change pull invites the
     * optimizer to hash/scan a mapping table that holds days of commits, for a number only
     * the panel reads.
     */
    static final String TX_LAG_SQL =
            "SELECT CASE WHEN tran_end_time > SYSUTCDATETIME() THEN 0"
            + " ELSE DATEDIFF(SECOND, tran_end_time, SYSUTCDATETIME()) * 1000 END"
            + " FROM cdc.lsn_time_mapping WHERE start_lsn = CONVERT(binary(10), ?, 1)";

    /**
     * The change function of one capture instance, as a delimited identifier:
     * {@code [cdc].[fn_cdc_get_all_changes_<name>]}.
     *
     * <p>P1-7 originally refused any name outside {@code [A-Za-z0-9_]}. That protected
     * the inline, but it also refused legitimate names: an explicit
     * {@code @capture_instance} may contain spaces or non-ASCII characters, and those
     * tables were then skipped for ever while the watermark kept moving — a silent data
     * loss for the one table the operator had explicitly enabled. Bracket-quoting keeps
     * the injection surface closed without rejecting names SQL Server itself accepts;
     * a {@code ]} inside a delimited identifier is written {@code ]]}.
     */
    static String captureFunction(String captureInstance) {
        return "[cdc].[fn_cdc_get_all_changes_"
                + captureInstance.replace("]", "]]") + "]";
    }

    /**
     * The names we can still not use: only an empty name (no function can be named that)
     * or one containing NUL (not representable in a T-SQL batch). Everything else is a
     * real identifier from a system table and is safe once delimited by
     * {@link #captureFunction(String)}.
     */
    static boolean isUsableCaptureInstanceName(String captureInstance) {
        return captureInstance != null && !captureInstance.isEmpty()
                && captureInstance.indexOf('\0') < 0;
    }

    /** This drain window's spiller, disposed when the window ends. */
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
     * Adds source tables created after auto-sync was enabled and creates their capture
     * instance (copying the role an existing instance uses). Runs between drain windows; a
     * failure leaves the existing tables streaming and is retried on the next scan.
     */
    private void reconcileAutoSync(long projectId) {
        if (!ctx.getConfig().isAutoSyncNewTables()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoSyncCheckMs < 60_000L) {
            return;
        }
        lastAutoSyncCheckMs = now;
        try {
            baselineService.reconcileAutoSync(ctx);
        } catch (Exception e) {
            log.warn("Project {}: auto-sync reconciliation failed; existing tables keep"
                    + " streaming and the new ones are retried on a later scan: {}",
                    projectId, e.toString());
            stats.setLastError("auto-sync: " + CdcFailureClassifier.describe(e));
        }
    }

    private void flushTransaction(long projectId, String txLsn,
                                  Map<CdcTableBinding, SqlServerTxFold.Buffer> buffers)
            throws Exception {
        if (buffers.isEmpty()) return;
        try (CdcTargetTransaction target = targetTransactions.open(project, ctx)) {
            try {
                // Upserts before deletes: with the fold's same-PK collapsing this order is
                // what makes PK chains (1→2→3 in one tx) land on the source's final state.
                for (Map.Entry<CdcTableBinding, SqlServerTxFold.Buffer> e : buffers.entrySet()) {
                    target.applyUpserts(e.getKey(), e.getValue().upserts());
                }
                for (Map.Entry<CdcTableBinding, SqlServerTxFold.Buffer> e : buffers.entrySet()) {
                    target.applyDeletes(e.getKey(), e.getValue().deletes());
                }
                // Fan-out only: the LSN every target worker must record on its own commit.
                target.notePosition(com.qqmu.jync.service.fanout.FanoutRuntime.TOKEN_LSN
                        + txLsn);
                target.commit();
            } catch (Exception e) {
                // P2-3: a flush failure may mean schema drift — the cached bindings that
                // built these statements may still carry columns the source dropped (or
                // miss new ones). Evict them so the retry re-reads metadata instead of
                // replaying the identical failing SQL until strike-out parks the project.
                for (CdcTableBinding b : buffers.keySet()) {
                    supplier.invalidate(b);
                }
                throw e;
            }
        }
        ctx.requireLease();
        positionStore.advanceLsn(projectId, txLsn);
        stats.recordPositionLsn(txLsn);
        applyFailures.set(0); // F-13: a landed transaction clears the strike-out tally
        if (targetRepair != null) {
            // A committed transaction proves the target is healthy again, so a later
            // deletion gets the full repair budget instead of the exhausted one.
            targetRepair.resetAttempts(projectId);
        }
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

    /** Database-level CDC flag, or null when undeterminable (old server, permissions). */
    private static Boolean readCdcDbEnabled(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()")) {
            return rs.next() ? rs.getBoolean(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * #2: canonical min valid LSN of one capture instance — the lower bound
     * fn_cdc_get_all_changes accepts for it — or NULL while its change table holds no
     * rows yet. Same CONVERT(varchar(32), …, 1) shape as every other LSN we handle.
     */
    private String readMinLsn(Connection conn, String captureInstance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CONVERT(varchar(32), sys.fn_cdc_get_min_lsn(?), 1)")) {
            ps.setString(1, captureInstance);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Discover every enabled capture instance on this DB and filter to the tables the
     * project actually wants.
     *
     * <p>A source table may have TWO capture instances at once: creating a new instance
     * ({@code sp_cdc_enable_table @capture_instance=...}) does not disable the old one.
     * Changes between the stored watermark and the new instance's {@code start_lsn} exist
     * only in the old instance, so keeping just the newest silently lost them. Both are
     * scanned; their rows share one binding (keyed by schema.table), so the fold merges the
     * duplicate events instead of applying them twice.
     */
    private List<CaptureInstance> discoverCaptureInstances(Connection conn) throws SQLException {
        // #2 also selects start_lsn (canonical form) — the discriminant that tells a
        // mid-flight-created CI (clamp and self-heal) from a purged one (park).
        // cdc.change_tables carries only source_object_id: the schema/table names are
        // resolved through the catalog functions. (The previous SQL selected
        // source_schema_name / source_table_name, which no SQL Server version has — every
        // real discovery failed with "Invalid column name", so the consumer never saw a
        // capture instance. Found by the chunking container IT.)
        String sql = "SELECT OBJECT_SCHEMA_NAME(source_object_id), OBJECT_NAME(source_object_id),"
                + " capture_instance, CONVERT(varchar(32), start_lsn, 1)"
                + " FROM cdc.change_tables"
                + " ORDER BY 1, 2, start_lsn";
        Map<String, List<CaptureInstance>> byTable = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                String ci = rs.getString(3);
                // Canonical (lower-case) — SQL Server hands the hex back upper-case through
                // some paths and lower-case through others, and the clamp decision below is
                // a plain lexicographic compare: 'B' < 'b' made a new capture instance look
                // older than the watermark, turning the self-heal into a bogus PURGED park.
                String startLsn = canonicalLsn(rs.getString(4));
                if (!matchesProjectTable(schema, table)) continue;
                if (!isUsableCaptureInstanceName(ci)) {
                    // Only an empty or NUL-bearing name is unquotable; it cannot be a
                    // function SQL Server created, so there is nothing to sync.
                    log.warn("Skipping capture instance '{}' of {}.{} — the name is empty"
                            + " or contains NUL and cannot be quoted as an identifier",
                            ci, schema, table);
                    continue;
                }
                String key = (schema + "." + table).toLowerCase(Locale.ROOT);
                byTable.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new CaptureInstance(schema, table, ci, startLsn));
            }
        }
        List<CaptureInstance> found = new ArrayList<>();
        byTable.values().forEach(found::addAll);
        noteCaptureInstanceChanges(found);
        return found;
    }

    /**
     * P1-6: log the capture instances this consumer has not seen before — the moment a
     * DBA-enabled CI becomes visible instead of silence until its first DML moves the
     * watermark — and the ones that disappear.
     *
     * <p>A vanished instance is a table that silently stops capturing: {@code
     * sp_cdc_disable_table} (or dropping the table) removes the row from {@code
     * cdc.change_tables}, and from then on the consumer has nothing to read for it while
     * every other instance keeps the watermark moving. Nothing else in the product says so,
     * so say it here. Forgetting the name lets a re-created instance be reported as new.
     */
    private void noteCaptureInstanceChanges(List<CaptureInstance> instances) {
        Set<String> found = new HashSet<>();
        for (CaptureInstance ci : instances) {
            found.add(ci.captureInstance);
            if (knownCaptureInstances.add(ci.captureInstance)) {
                log.info("SQLServer CDC for project {} picked up capture instance {}"
                        + " ({}.{}); its changes will be synced",
                        project.getId(), ci.captureInstance, ci.schema, ci.table);
            }
        }
        for (java.util.Iterator<String> it = knownCaptureInstances.iterator(); it.hasNext(); ) {
            String name = it.next();
            if (!found.contains(name)) {
                it.remove();
                log.warn("SQLServer CDC for project {} no longer sees capture instance {} in"
                        + " cdc.change_tables — CDC was disabled for that table, or the table"
                        + " was dropped, mid-stream. Changes made to it from now on are NOT"
                        + " captured: re-enable CDC on the table and re-baseline it",
                        project.getId(), name);
            }
        }
    }

    /**
     * Throttled once per {@link #DISCOVERY_REFRESH_MS}: every selected table has no capture
     * instance, so the project captures nothing. Silent before, which made a CDC project that
     * had never been enabled on the source look identical to one that was simply idle.
     */
    /**
     * The capture-instance set changes only when a DBA enables/repairs CDC, so the busy path
     * uses the same 60-second cache as the idle path instead of running a catalog query for
     * every coalesced poll.
     */
    /** Test seam: make the next drain re-read cdc.change_tables. */
    void expireDiscoveryForTests() {
        lastDiscoveryAtMs = 0L;
    }

    private List<CaptureInstance> captureInstances(Connection conn) throws SQLException {
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryAtMs < DISCOVERY_REFRESH_MS) {
            return cachedCaptureInstances;
        }
        lastDiscoveryAtMs = now;
        cachedCaptureInstances = discoverCaptureInstances(conn);
        return cachedCaptureInstances;
    }

    private void warnNoCaptureInstances() {
        if (!knownCaptureInstances.isEmpty()) {
            // Discovery already named each instance as it vanished; a second, vaguer line
            // every minute would only bury it.
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNoInstanceWarnMs < DISCOVERY_REFRESH_MS) {
            return;
        }
        lastNoInstanceWarnMs = now;
        log.warn("SQLServer CDC for project {} sees no capture instance for any selected table,"
                + " so nothing will be captured until a DBA runs sys.sp_cdc_enable_table on"
                + " them. The consumer keeps polling and picks the instance up as soon as it"
                + " appears.", project.getId());
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
     * Binding columns the capture instance's change table does not carry, in binding
     * order. Empty when the binding is absent/unsupported (that case is counted per row
     * by the scan loop, as before).
     */
    static List<String> missingBindingColumns(CdcTableBinding binding, Set<String> availableCols) {
        if (binding == null || !binding.isApplicable()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String col : binding.columnNames()) {
            if (!availableCols.contains(col.toLowerCase(Locale.ROOT))) {
                missing.add(col);
            }
        }
        return missing;
    }


    /**
     * The per-window column plan of one capture instance's change-table ResultSet: the
     * binding columns the CT carries, their ResultSet indexes, and their metadata —
     * resolved once on the window's first row instead of per row and per column.
     *
     * <p>Reads the captured columns into a map keyed by column name (case-preserved).
     *
     * <p>Columns the CT does NOT carry are omitted from the row map, but they are never
     * applied half-way: {@code scanCi} refuses a capture instance whose CT lacks any
     * binding column before a single row is folded (the applier binds every binding
     * column and a missing map key becomes an explicit NULL). The skip here is only a
     * safety net for a schema that changes between the metadata read and the CT scan.
     *
     * <p>True SQLException (connection drop, type mismatch) propagates — the drain
     * aborts and the watermark does not advance, so the same rows re-read on retry.
     */
    static final class RowReader {

        private final List<String> names;
        private final int[] indexes;
        private final ColumnMeta[] metas;

        private RowReader(List<String> names, int[] indexes, ColumnMeta[] metas) {
            this.names = names;
            this.indexes = indexes;
            this.metas = metas;
        }

        /**
         * Null when the table cannot be applied at all (absent or unsupported binding):
         * the scan loop counts those rows as unsupported before reading anything.
         */
        static RowReader of(ResultSet rs, CdcTableBinding binding, Set<String> availableCols)
                throws SQLException {
            return of(rs, binding, availableCols, null);
        }

        /**
         * A reader limited to {@code onlyColumns} (matched case-insensitively against the
         * binding's own columns), or every binding column when {@code onlyColumns} is null.
         *
         * <p>Delete ({@code __$operation=1}) and update-before ({@code =3}) images are never
         * applied whole: the fold keeps only their primary keys, so reading the rest of the
         * payload — LOBs included — is wasted driver work on every delete and every update.
         */
        static RowReader of(ResultSet rs, CdcTableBinding binding, Set<String> availableCols,
                            Set<String> onlyColumns) throws SQLException {
            if (binding == null || !binding.isApplicable()) {
                return null;
            }
            Set<String> wanted = null;
            if (onlyColumns != null) {
                wanted = new HashSet<>();
                for (String column : onlyColumns) {
                    wanted.add(column.toLowerCase(Locale.ROOT));
                }
            }
            List<String> names = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            List<ColumnMeta> metas = new ArrayList<>();
            for (String col : binding.columnNames()) {
                String lower = col.toLowerCase(Locale.ROOT);
                if (!availableCols.contains(lower)) {
                    continue; // not captured by this CDC instance — leave absent
                }
                if (wanted != null && !wanted.contains(lower)) {
                    continue; // only the key is needed from this image
                }
                names.add(col);
                indexes.add(rs.findColumn(col));
                metas.add(binding.getMeta().column(col).orElse(null));
            }
            return new RowReader(names,
                    indexes.stream().mapToInt(Integer::intValue).toArray(),
                    metas.toArray(new ColumnMeta[0]));
        }

        Map<String, Object> read(ResultSet rs) throws SQLException {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String col = names.get(i);
                int index = indexes[i];
                ColumnMeta column = metas[i];
                Object normalized = column != null
                        && JdbcValueNormalizer.handles(column.getTypeName())
                        ? JdbcValueNormalizer.read(rs, index, column.getJdbcType(),
                                column.getTypeName(), DatabaseType.SQLSERVER)
                        : JdbcValueNormalizer.UNHANDLED;
                if (normalized != JdbcValueNormalizer.UNHANDLED) {
                    // UNIQUEIDENTIFIER/MONEY/DATETIMEOFFSET/XML/geometry: the identity the
                    // target driver can actually bind (String/BigDecimal/OffsetDateTime/WKB).
                    row.put(col, normalized);
                    continue;
                }
                Object value = rs.getObject(index);
                // Remaining unknown types (hierarchyid, geography) arrive as
                // driver-proprietary classes that the target JDBC cannot bind. Fall back
                // to getString() so the row still flows through — with a string, the
                // target driver has a coercion path.
                if (value != null && looksLikeDriverProprietary(value.getClass().getName())) {
                    row.put(col, rs.getString(index));
                } else {
                    row.put(col, value);
                }
            }
            return row;
        }
    }

    private static boolean looksLikeDriverProprietary(String className) {
        return className.startsWith("com.microsoft.sqlserver.")
                || className.startsWith("com.microsoft.sqlserver.jdbc.")
                || className.contains("hierarchyid")
                || className.contains("SqlXml");
    }

    private boolean applyStrikeOut() { return applyFailures.get() > MAX_APPLY_FAILURES; }

    /**
     * The persisted LSN when it is ahead of {@code anchor}, else the anchor itself. LSNs from
     * {@code CONVERT(varchar, lsn, 1)} are fixed-width lowercase hex with a {@code 0x} prefix,
     * so lexicographic order is LSN order; a value in any other shape is ignored rather than
     * compared.
     */
    private String newestPersistedLsn(long projectId, String anchor) {
        try {
            return positionStore.find(projectId)
                    .map(CdcPosition::getLsn)
                    .filter(lsn -> lsn != null && anchor != null
                            && lsn.length() == anchor.length()
                            && lsn.compareToIgnoreCase(anchor) > 0)
                    .orElse(anchor);
        } catch (RuntimeException e) {
            // A metadata hiccup must not turn into a second failure: keep the anchor.
            return anchor;
        }
    }

    private void fatalStop(long projectId, Throwable failure, CdcFailureClassifier.Kind kind) {
        // F-13: SQL Server has no parse stage, so a TRANSIENT park is always the
        // apply-side strike-out (repeated target-flush failures); anything else keeps
        // its classifier kind (PURGED / AUTH_FAILURE / SCHEMA_CONFLICT / …).
        String label = kind == CdcFailureClassifier.Kind.TRANSIENT
                ? "APPLY_STRIKE_OUT" : kind.name();
        String detail = label + ": " + (failure == null
                ? "repeated SQLServer CDC failures" : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("SQLServer consumer for project {} parking in ERROR — {}", projectId, detail);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    /** Writes the throttled position; a failure here must never fail the cycle. */
    private void flushPosition(Long projectId) {
        try {
            positionStore.flush(projectId);
        } catch (RuntimeException e) {
            log.warn("Could not flush the CDC position for project {}: {}",
                    projectId, e.getMessage());
        }
    }

    @Override
    public void requestStop() {
        // Do NOT close the source connection here: ps.executeQuery() would throw
        // "Connection closed" mid-drain, which classify() might bucket as PERMANENT
        // and park the project in ERROR over a deliberate stop. Cancel the statement
        // instead: the drain's own error path handles it, and it unblocks a scan that is
        // stuck in the driver on a large window.
        running = false;
        Statement active = activeStatement;
        if (active != null) {
            try {
                active.cancel();
            } catch (SQLException | RuntimeException ignored) {
                // Already closed, or cancel unsupported — the stop flag still applies.
            }
        }
    }

    private static final class CaptureInstance {
        final String schema;
        final String table;
        final String captureInstance;
        /**
         * #2: canonical start_lsn from cdc.change_tables — the LSN this CI began
         * capturing at. Compared against the stored watermark on a 22054 to tell a
         * mid-flight-created CI (start_lsn above the watermark → clamp, self-heal)
         * from a purged one (start_lsn at/below it → park). The canonical "0x" +
         * 20-lowercase-hex form is fixed-width, so String.compareTo == LSN order.
         */
        final String startLsn;
        CaptureInstance(String schema, String table, String captureInstance, String startLsn) {
            this.schema = schema;
            this.table = table;
            this.captureInstance = captureInstance;
            this.startLsn = startLsn;
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
            String key = keyOf(ci.schema, ci.table);
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

        /**
         * P2-3: drop whatever is cached for this binding's table (binding or negative
         * sentinel) so the next {@link #binding} call re-reads live metadata. Called
         * when a flush fails: the cached column set that built the failing SQL is
         * suspect (source schema drift), and replaying it verbatim would fail
         * identically every retry until strike-out.
         */
        void invalidate(CdcTableBinding binding) {
            cache.remove(keyOf(binding.getDatabase(), binding.getTable()));
        }

        /** Cache keys are lowercase "schema.table"; BindingSupplier stores ci.schema as the binding's database. */
        private static String keyOf(String schema, String table) {
            return (schema + "." + table).toLowerCase(Locale.ROOT);
        }
    }
}
