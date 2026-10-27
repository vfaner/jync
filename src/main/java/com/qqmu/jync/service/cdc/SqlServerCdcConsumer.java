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

    private final CdcEventApplier applier;
    private final BindingSupplier supplier = new BindingSupplier();

    /** P1-6: discovery throttle + the CI names this consumer has already logged. */
    private long lastDiscoveryAtMs;
    private final Set<String> knownCaptureInstances = new HashSet<>();
    /**
     * Capture instance → the missing-column description last warned about. A CI whose
     * change table lacks a binding column is skipped every cycle; without this the same
     * WARN would repeat on every poll, and without the per-CI key two unhealthy instances
     * would flap each other's single-throttle slot.
     */
    private final Map<String, String> missingColumnWarnings = new java.util.concurrent.ConcurrentHashMap<>();
    /** #4: throttle for the "CDC disabled at DB level" warning (see drain()). */
    private long lastCdcOffWarnMs;

    private volatile boolean running = true;
    private volatile Connection currentSource;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    /** F-13: consecutive failed drain cycles; reset to 0 on every successful flush. */
    private final AtomicInteger applyFailures = new AtomicInteger();
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
                    ChangeType.INFO, "SQLServer consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the SQLServer start log for project {}: {}",
                    projectId, e.getMessage());
        }
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
                } catch (Throwable e) {
                    // Any drain exception leaves fromLsn unchanged: the next iteration
                    // re-reads the same interval (data-first at-least-once).
                    applyFailures.incrementAndGet(); // F-13: count the failed cycle
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
                    currentSource = null;
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
        if (maxLsn.equalsIgnoreCase(fromLsn)) {
            // P1-6: the idle early return must not starve discovery — re-read
            // cdc.change_tables on a throttled cadence so a newly enabled capture
            // instance is seen (and logged) now, not at its first DML. Refresh only:
            // the (fromLsn, maxLsn] interval is empty by definition here.
            long now = System.currentTimeMillis();
            if (now - lastDiscoveryAtMs >= DISCOVERY_REFRESH_MS) {
                lastDiscoveryAtMs = now;
                discoverCaptureInstances(conn);
            }
            return out; // nothing new
        }
        lastDiscoveryAtMs = System.currentTimeMillis();
        List<CaptureInstance> instances = discoverCaptureInstances(conn);
        if (instances.isEmpty()) {
            stats.addSkippedUnsupported(1);
            // No capture instance means nothing will ever show up past fromLsn without
            // DBA action: do NOT advance the watermark — that would hide the gap. Advance
            // to maxLsn only when at least one tracked instance exists, so the next poll
            // starts from the current position when the DBA enables CDC mid-stream.
            return out;
        }
        // P0-6: fold rows per transaction in SOURCE ORDER (the SQL guarantees
        // ORDER BY __$start_lsn, __$seqval): same-PK last-op-wins, op=3/op=4 pairs
        // delete the old PK when an UPDATE moved it, and transactions flush in
        // ascending txLsn order so advanceLsn stays monotonic across capture instances.
        SqlServerTxFold fold = new SqlServerTxFold(
                properties.getCdc().getMaxTransactionRows(),
                properties.getCdc().getMaxTransactionBytes());
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
            try {
                scanCi(conn, ci, changesSql(ci.captureInstance), fromLsn, maxLsn, fold, out);
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
                if (e.getErrorCode() == 22054) {
                    String minLsn = readMinLsn(conn, ci.captureInstance);
                    if (ci.startLsn != null && ci.startLsn.compareTo(fromLsn) > 0) {
                        // ① new CI. min_lsn is NULL while its change table holds no rows
                        // yet — start_lsn is then the lowest LSN the function accepts.
                        String clampTo = minLsn != null ? minLsn : ci.startLsn;
                        if (clampTo.compareTo(maxLsn) > 0) {
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
                        scanCi(conn, ci, changesSqlInclusive(ci.captureInstance), clampTo,
                                maxLsn, fold, out);
                        continue;
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
            flushTransaction(projectId, e.getKey(), e.getValue());
        }
        // #3: reaching here means the loop completed without aborting — every tracked
        // CI was scanned over (fromLsn, maxLsn] (normally, or from a #2 clamp point).
        // Rows were either folded or deliberately skipped as unsupported, so the window
        // is fully consumed and the watermark advances to maxLsn EVEN WHEN NOTHING
        // FOLDED. The old per-row highWater only advanced on a tracked row: a project
        // whose tracked tables are quiet while untracked tables churn kept fromLsn
        // pinned, re-running full discovery over a monotonically growing window every
        // cycle until aged-out logs tripped 22054. Guarded by running: an interrupted
        // scan did not finish the window and must not advance it. The persisted
        // position still only moves in flushTransaction (data-first); after a restart
        // the quiet window re-scans once, harmlessly (unsupported rows skip again).
        if (running && !Thread.currentThread().isInterrupted()) {
            out.nextLsn = maxLsn;
        }
        return out;
    }

    /**
     * Scan one capture instance over the window its SQL defines — (fromBind, toLsn]
     * for {@link #changesSql}, [fromBind, toLsn] for the inclusive #2 clamp variant —
     * folding every row into {@code fold}. Shared by the normal per-cycle pull and the
     * clamp rescan so both paths fold identically.
     */
    private void scanCi(Connection conn, CaptureInstance ci, String sql, String fromBind,
                        String toLsn, SqlServerTxFold fold, DrainResult out)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fromBind);
            ps.setString(2, toLsn);
            try (ResultSet rs = ps.executeQuery()) {
                // Cache the real column set of the CT row ONCE per ci, on the first row
                // (an empty window must not cost a metadata read) — readRow consults it
                // to distinguish "not captured" from genuine errors.
                Set<String> availableCols = null;
                while (running && !Thread.currentThread().isInterrupted() && rs.next()) {
                    if (availableCols == null) {
                        availableCols = availableColumns(rs);
                        List<String> missingCols =
                                missingBindingColumns(supplier.binding(ci), availableCols);
                        if (!missingCols.isEmpty()) {
                            // @captured_column_list captured only a subset of the table (or
                            // the column was added after the instance was created). The
                            // applier binds EVERY binding column and JdbcRowMapper turns a
                            // missing map key into an explicit NULL, so folding these rows
                            // would overwrite the target's values — silently when the
                            // column is nullable. Nothing in such a window can be applied
                            // losslessly, so skip the whole instance, count the dropped
                            // events, and tell the DBA which columns to re-capture. The
                            // watermark still advances at the end of the drain (the same
                            // deliberate-skip semantics as unsupported rows).
                            long skipped = 1L; // current row
                            while (running && !Thread.currentThread().isInterrupted()
                                    && rs.next()) {
                                skipped++;
                            }
                            stats.addSkippedUnsupported(skipped);
                            warnMissingCapturedColumns(ci, missingCols);
                            return;
                        }
                        missingColumnWarnings.remove(ci.captureInstance);
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
                    int op = rs.getInt(2);
                    CdcTableBinding binding = supplier.binding(ci);
                    if (binding == null || !binding.isApplicable()) {
                        stats.addUnsupportedTable(ci.schema + "." + ci.table);
                        continue;
                    }
                    Map<String, Object> row = readRow(rs, binding, availableCols);
                    if (op == 1 || op == 4) {
                        // Insert / update-after images reach the target whole. Delete and
                        // update-before images only supply primary keys to the fold.
                        row = spiller().spill(binding, row);
                    }
                    fold.accept(startLsn, binding, op, row);
                    out.rows++;
                    stats.touchEvent(System.currentTimeMillis());
                }
            }
        }
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
                + " FROM cdc.fn_cdc_get_all_changes_" + captureInstance
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
                + " FROM cdc.fn_cdc_get_all_changes_" + captureInstance
                + "(CONVERT(binary(10), ?, 1), CONVERT(binary(10), ?, 1),'all update old')"
                + " ORDER BY __$start_lsn, __$seqval";
    }

    /**
     * P1-7: capture instance names come from a system table and are concatenated into
     * SQL — they are identifiers, and SQL Server identifiers used here only ever contain
     * letters, digits and underscore. Anything else is skipped (not executed).
     */
    static boolean isSafeCaptureInstanceName(String captureInstance) {
        if (captureInstance == null || captureInstance.isEmpty()) {
            return false;
        }
        for (int i = 0; i < captureInstance.length(); i++) {
            char c = captureInstance.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
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

    private void flushTransaction(long projectId, String txLsn,
                                  Map<CdcTableBinding, SqlServerTxFold.Buffer> buffers)
            throws Exception {
        if (buffers.isEmpty()) return;
        Connection target = ctx.getConnectionProvider().open(ctx.getTargetConfig());
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
            // Upserts before deletes: with the fold's same-PK collapsing this order is
            // what makes PK chains (1→2→3 in one tx) land on the source's final state.
            for (Map.Entry<CdcTableBinding, SqlServerTxFold.Buffer> e : buffers.entrySet()) {
                if (!e.getValue().upserts.isEmpty()) {
                    applier.applyUpserts(target, e.getKey(), e.getValue().upserts);
                }
            }
            for (Map.Entry<CdcTableBinding, SqlServerTxFold.Buffer> e : buffers.entrySet()) {
                if (!e.getValue().deletes.isEmpty()) {
                    applier.applyDeletes(target, e.getKey(), e.getValue().deletes);
                }
            }
            target.commit();
        } catch (Exception e) {
            try { target.rollback(); } catch (SQLException ignored) { }
            // P2-3: a flush failure may mean schema drift — the cached bindings that
            // built these statements may still carry columns the source dropped (or
            // miss new ones). Evict them so the retry re-reads metadata instead of
            // replaying the identical failing SQL until strike-out parks the project.
            for (CdcTableBinding b : buffers.keySet()) {
                supplier.invalidate(b);
            }
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
        applyFailures.set(0); // F-13: a landed transaction clears the strike-out tally
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
     * project actually wants. When a source table has MULTIPLE capture instances (schema
     * change → disable/enable), keep only the one with the highest start_lsn — the
     * others are historical and would double-apply the same row events.
     */
    private List<CaptureInstance> discoverCaptureInstances(Connection conn) throws SQLException {
        // ORDER BY start_lsn ASC lets the per-table loop pick the LAST (newest) one.
        // #2 also selects start_lsn (canonical form) — the discriminant that tells a
        // mid-flight-created CI (clamp and self-heal) from a purged one (park).
        String sql = "SELECT source_schema_name, source_table_name, capture_instance,"
                + " CONVERT(varchar(32), start_lsn, 1)"
                + " FROM cdc.change_tables"
                + " ORDER BY source_schema_name, source_table_name, start_lsn";
        Map<String, CaptureInstance> byTable = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                String ci = rs.getString(3);
                String startLsn = rs.getString(4); // canonical — lexicographic == LSN order
                if (!matchesProjectTable(schema, table)) continue;
                if (!isSafeCaptureInstanceName(ci)) {
                    // P1-7: the name is concatenated into the change-pull SQL; a name
                    // outside [A-Za-z0-9_] never reaches the statement.
                    log.warn("Skipping capture instance '{}' of {}.{} — name contains"
                            + " characters outside [A-Za-z0-9_]; its changes will NOT be"
                            + " synced until the instance is recreated with a safe name",
                            ci, schema, table);
                    continue;
                }
                String key = (schema + "." + table).toLowerCase(Locale.ROOT);
                byTable.put(key, new CaptureInstance(schema, table, ci, startLsn)); // last one wins
            }
        }
        List<CaptureInstance> found = new ArrayList<>(byTable.values());
        noteNewCaptureInstances(found);
        return found;
    }

    /**
     * P1-6: log the capture instances this consumer has not seen before — the moment a
     * DBA-enabled CI becomes visible instead of silence until its first DML moves the
     * watermark. Add-only set: a dropped CI keeps its name (logged once per lifetime).
     */
    private void noteNewCaptureInstances(List<CaptureInstance> instances) {
        for (CaptureInstance ci : instances) {
            if (knownCaptureInstances.add(ci.captureInstance)) {
                log.info("SQLServer CDC for project {} picked up capture instance {}"
                        + " ({}.{}); its changes will be synced",
                        project.getId(), ci.captureInstance, ci.schema, ci.table);
            }
        }
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
     * One WARN per (capture instance, missing-column set). The status panel's
     * {@code unsupportedTables} set is the persistent signal; this line is the actionable
     * one and must name the remediation, since nothing else tells the DBA why a tracked
     * table stopped syncing.
     */
    private void warnMissingCapturedColumns(CaptureInstance ci, List<String> missingCols) {
        String description = String.join(", ", missingCols);
        if (description.equals(missingColumnWarnings.put(ci.captureInstance, description))) {
            return;
        }
        stats.addUnsupportedTable(ci.schema + "." + ci.table);
        log.warn("SQLServer CDC: capture instance {} of {}.{} does not capture column(s)"
                        + " [{}] — every change in this table is being SKIPPED (applying the"
                        + " rows would overwrite the target's values with NULL). Recreate the"
                        + " instance capturing all columns (sys.sp_cdc_disable_table, then"
                        + " sys.sp_cdc_enable_table with @captured_column_list = NULL or the"
                        + " full list), then re-baseline this table",
                ci.captureInstance, ci.schema, ci.table, description);
    }

    /**
     * Read the captured columns into a map keyed by column name (case-preserved).
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
    private Map<String, Object> readRow(ResultSet rs, CdcTableBinding binding,
                                        Set<String> availableCols) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String col : binding.columnNames()) {
            if (!availableCols.contains(col.toLowerCase(Locale.ROOT))) {
                continue; // not captured by this CDC instance — leave absent
            }
            ColumnMeta column = binding.getMeta().column(col).orElse(null);
            Object normalized = column != null && JdbcValueNormalizer.handles(column.getTypeName())
                    ? JdbcValueNormalizer.read(rs, rs.findColumn(col), column.getJdbcType(),
                            column.getTypeName(), DatabaseType.SQLSERVER)
                    : JdbcValueNormalizer.UNHANDLED;
            if (normalized != JdbcValueNormalizer.UNHANDLED) {
                // UNIQUEIDENTIFIER/MONEY/DATETIMEOFFSET/XML/geometry: the identity the
                // target driver can actually bind (String/BigDecimal/OffsetDateTime/WKB).
                row.put(col, normalized);
                continue;
            }
            Object value = rs.getObject(col);
            // Remaining unknown types (hierarchyid, geography) arrive as driver-proprietary
            // classes that the target JDBC cannot bind. Fall back to getString() so the row
            // still flows through — with a string, the target driver has a coercion path.
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

    private boolean applyStrikeOut() { return applyFailures.get() > MAX_APPLY_FAILURES; }

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
