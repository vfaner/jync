package com.qqmu.jync.service.cdc;

import java.sql.Connection;
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
import com.qqmu.jync.model.DatabaseConfig;
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
 * Long-lived Oracle LogMiner CDC pipeline (v1.6.0). Lifecycle mirrors {@link CdcConsumer}
 * one-for-one: baseline gate → endless mine/parse/apply loop with lease renewal at the top,
 * requestStop as the quiet exit, fatal-stop on PURGED/AUTH/PARSE_STRIKE_OUT/APPLY_STRIKE_OUT,
 * catch-all
 * quiet-vs-park distinction, finally-block lease release + INFO stop line.
 *
 * <p>Position discipline (data-first): one CSCN = one target commit → lease re-check →
 * {@code positionStore.advanceScn}. A crash between commit and advanceScn replays the whole
 * transaction; upserts are idempotent.
 *
 * <p>Differences from the binlog consumer are all forced by the source:
 * <ul>
 *   <li>position is a single SCN (no binlog file/pos/gtid triple);</li>
 *   <li>each mine cycle opens a fresh {@link LogMinerSession} (logfiles rotate, online redo
 *       switches); an ORA-01291 mid-mine is classified PURGED and fatally parks;</li>
 *   <li>transactions are batched by CSCN change (COMMITTED_DATA_ONLY guarantees one XID per
 *       CSCN);</li>
 *   <li>rows arrive as SQL_REDO text parsed by {@link SqlRedoParser}; unsupported statements
 *       are skipped + counted, never guessed.</li>
 * </ul>
 */
@Slf4j
public final class LogMinerCdcConsumer implements ManagedCdcConsumer {

    /** DDL SQL_REDO truncation for WARN logs — same cap as the binlog handler (200 chars). */
    private static final int DDL_LOG_SQL_CHARS = 200;
    /** Named in warnings and in the fail-policy park message, so the remedy is searchable. */
    static final String MISSING_LOB_POLICY_KEY = "sync.cdc.logminer.missing-lob-policy";
    /** Parse strike-out: same threshold as {@link CdcConsumer} (5 consecutive poison rows). */
    static final int MAX_PARSE_FAILURES = 5;
    /**
     * F-12: apply/cycle strike-out — same threshold as the PG consumer's APPLY_STRIKE_OUT
     * and the binlog consumer's deserializationFailures (more than 5 consecutive failed
     * mine cycles with no successfully applied transaction between them).
     */
    static final int MAX_APPLY_FAILURES = 5;

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
    private final LogMinerSessionFactory sessionFactory;

    private final CdcEventApplier applier;
    private final SqlRedoParser parser = new SqlRedoParser();
    private final BindingSupplier supplier = new BindingSupplier();

    private volatile boolean running = true;
    private volatile Connection currentSource;
    /** P0-2: second connection to CDB$ROOT when mining via a common user; null otherwise. */
    private volatile Connection currentMining;
    private volatile LogMinerSession currentSession;
    /** P0-2: decided once per consumer lifetime; see {@link #decideMiningPlan}. */
    private volatile MiningPlan miningPlan;
    /** P0-2: CDB name (= root service name), resolved with the plan when ROOT. */
    private volatile String miningCdbName;
    /**
     * CON_ID of the connection's current container. On a non-CDB this is 0/1 and the
     * SRC_CON_ID filter below is a no-op. On a CDB mined from CDB$ROOT, only rows with
     * this con_id are in scope; rows from other PDBs are discarded.
     */
    private volatile int ownConId = -1;

    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicInteger parseFailures = new AtomicInteger();
    /** F-12: consecutive failed mine cycles (target flush rejection, connect failure...). */
    private final AtomicInteger applyFailures = new AtomicInteger();
    private final AtomicLong connectedAtNs = new AtomicLong();

    private CdcReconnectBackoff backoff;

    /** Per-table aggregated change-log flusher (10s windows, final flush on stop). */
    private final CdcChangeWindowLogger windowLogger;

    /** LOB spill store for buffered rows, built on first use; null when switched off. */
    private volatile LobSpillStore lobSpillStore;
    /** Owns the files of the buffered transaction; null between transactions. */
    private CdcLobSpiller spiller;

    public LogMinerCdcConsumer(Project project, SyncLockService.LockHandle lock,
                               SyncContext ctx, CdcPositionStore positionStore,
                               CdcBaselineService baselineService, SyncTaskStore taskStore,
                               SyncStateWriter stateWriter, DataSourceManager dataSourceManager,
                               CdcRuntimeStats stats, SyncProperties properties,
                               LogMinerSessionFactory sessionFactory) {
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
        this.sessionFactory = sessionFactory;
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
                    ChangeType.INFO, "LogMiner consumer started", true, 0L);
        } catch (RuntimeException e) {
            log.warn("Could not record the LogMiner start log for project {}: {}",
                    projectId, e.getMessage());
        }
        try {
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !Boolean.TRUE.equals(position.isBaselineDone())) {
                baselineService.runBaseline(ctx, lock::renew);
                position = positionStore.find(projectId).orElse(null);
            }
            if (position == null || position.getScn() == null) {
                throw new IllegalStateException("LogMiner baseline completed without a"
                        + " recorded SCN anchor; refusing to mine from an unknown position");
            }
            long lastScn = position.getScn();
            backoff = new CdcReconnectBackoff(
                    properties.getCdc().getReconnectBackoffMs(),
                    properties.getCdc().getReconnectBackoffMaxMs());
            long idlePollMs = CdcReconnectBackoff.effectiveIdlePollMs(
                    ctx.getConfig().getCdcIdlePollMs(),
                    properties.getCdc().getLogminer().getIdlePollMs());
            windowLogger.start();
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!lock.renew()) {
                    log.warn("LogMiner consumer for project {} stopping: lease lost (another"
                            + " node may have taken over)", projectId);
                    stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                            ChangeType.INFO, "LogMiner consumer stopped: lease lost", true, 0L);
                    break;
                }
                lastFailure.set(null);
                connectedAtNs.set(0L);
                List<LogMinerSession.Row> drained;
                try {
                    Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig());
                    currentSource = conn;
                    // P0-2: on a CDB the session must run on a CDB$ROOT connection made
                    // with the mining account; on anything else this returns conn itself.
                    Connection miningConn = resolveMiningConnection(conn, projectId);
                    int batchSize = (int) Math.min(Integer.MAX_VALUE,
                            properties.getCdc().getLogminer().getBatchSize());
                    LogMinerSession session = sessionFactory.open(miningConn, lastScn, batchSize);
                    currentSession = session;
                    stats.setConnected(true);
                    stats.markStarted();
                    stats.setLastError(null);
                    connectedAtNs.set(System.nanoTime());
                    drained = drain(session);
                    // C2 fix: advance the watermark to THIS session's high-water so the
                    // next open does not re-ADD_LOGFILE the entire archive stream from the
                    // baseline SCN. positionStore.advanceScn already persisted each
                    // committed CSCN during drain; here we only update the local anchor so
                    // the next session opens at CURRENT_SCN, not at the baseline.
                    lastScn = session.highWaterScn();
                    session.close();
                } catch (Throwable e) {
                    // F-12: count failed cycles. drain's per-row parse skips are counted
                    // separately (parseFailures); anything that makes it here — target
                    // flush rejection, source/mining connect failure, session open — is
                    // a failed cycle and escalates after MAX_APPLY_FAILURES in a row.
                    applyFailures.incrementAndGet();
                    lastFailure.compareAndSet(null, e);
                    drained = Collections.emptyList();
                } finally {
                    closeQuietly(currentSession);
                    closeQuietly(currentMining);
                    currentMining = null;
                    closeQuietly(currentSource);
                    currentSession = null;
                    currentSource = null;
                    stats.setConnected(false);
                }
                if (!running) {
                    break;
                }
                Throwable failure = lastFailure.get();
                if (failure == null) {
                    // F-12 counts CONSECUTIVE failed cycles. A cycle that completed
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
                // R3b mirror: reset the backoff only when the session demonstrably lived.
                long connectedAt = connectedAtNs.get();
                long dwellMs = connectedAt == 0L
                        ? 0L : (System.nanoTime() - connectedAt) / 1_000_000L;
                if (dwellMs >= CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS) {
                    backoff.reset();
                }
                // 繁忙轮(捞到数据)立即开下一轮,把高峰延迟压到一个挖掘周期;
                // 只有真正空闲才休眠 idlePollMs,失败走指数退避。
                long delay = CdcReconnectBackoff.nextCycleDelayMs(
                        failure, !drained.isEmpty(), backoff, idlePollMs);
                stats.setLastError(failure == null
                        ? "mining cycle drained" : CdcFailureClassifier.describe(failure));
                log.warn("LogMiner cycle for project {} ended ({}); next cycle in {} ms",
                        projectId, failure == null ? "no failure recorded"
                                : CdcFailureClassifier.describe(failure), delay);
                if (!sleepWhileRunning(delay)) {
                    break;
                }
            }
        } catch (RuntimeException | Error e) {
            if (e instanceof LockLostException) {
                // R1: the baseline lost the lease to another node; that node owns the
                // position row and will redo the baseline. Quiet exit, never ERROR.
                log.info("LogMiner consumer for project {} stopped: lease lost during the"
                        + " baseline ({}); not parking it in ERROR", projectId, e.toString());
            } else if (!running || Thread.currentThread().isInterrupted()) {
                log.info("LogMiner consumer for project {} stopped by request while unwinding"
                        + " ({}); not parking it in ERROR", projectId, e.toString());
            } else {
                String detail = CdcFailureClassifier.describe(e);
                stats.setLastError(detail);
                taskStore.recordCdcFailure(projectId, detail);
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.ERROR, "LogMiner consumer failed: " + detail, false, 0L);
                log.error("LogMiner consumer for project {} failed", projectId, e);
            }
        } finally {
            closeQuietly(currentSession);
            closeQuietly(currentMining);
            currentMining = null;
            closeQuietly(currentSource);
            windowLogger.close();
            lock.close();
            stats.setConnected(false);
            try {
                stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                        ChangeType.INFO, "LogMiner consumer stopped", true, 0L);
            } catch (RuntimeException e) {
                log.warn("Could not record the LogMiner stop log for project {}: {}",
                        projectId, e.getMessage());
            }
        }
    }

    /** P0-2: where the LogMiner session runs. */
    enum MiningPlan {
        /** Mine on the configured source connection (non-CDB, or a direct root connection). */
        SOURCE,
        /** Mine on a second CDB$ROOT connection opened with the mining account. */
        ROOT
    }

    /**
     * The CDB mining-mode decision, as a pure function for direct testing.
     *
     * <ul>
     *   <li>not a CDB (or indeterminate — 11g has no CDB column) → SOURCE, the v1.6.0 path;</li>
     *   <li>connected to CDB$ROOT (CON_ID 1) → SOURCE, the legacy root-only capture: only
     *       root-container objects match, which someone may rely on, so it stays but the
     *       caller WARNs once about the capture scope;</li>
     *   <li>PDB connection + mining account configured → ROOT;</li>
     *   <li>PDB connection without one → fail fast with ORA-65040's own error code, naming
     *       the fix, instead of dying at the first START_LOGMNR with a bare Oracle message.</li>
     * </ul>
     */
    static MiningPlan decideMiningPlan(Boolean isCdb, int conId, boolean minerConfigured)
            throws SQLException {
        if (!Boolean.TRUE.equals(isCdb) || conId <= 1) {
            return MiningPlan.SOURCE;
        }
        if (minerConfigured) {
            return MiningPlan.ROOT;
        }
        throw new SQLException("ORA-65040: source is a CDB and DBMS_LOGMNR may only run in"
                + " CDB$ROOT — configure the mining account (miner username/password, a"
                + " C## common user) on this connection, or the first mine will fail with"
                + " ORA-65040 anyway");
    }

    /**
     * Resolve which connection this cycle's session runs on, deciding the mining plan on
     * first call. SOURCE returns {@code conn}; ROOT opens a fresh unpooled CDB$ROOT
     * connection via the mining account (tracked in {@link #currentMining} so requestStop
     * and the finally blocks close it).
     */
    private Connection resolveMiningConnection(Connection conn, long projectId)
            throws SQLException {
        if (ownConId < 0) {
            int cid = OracleCdbSupport.currentConId(conn);
            ownConId = Math.max(cid, 0);
        }
        if (miningPlan == null) {
            Boolean cdb = OracleCdbSupport.isCdb(conn);
            DatabaseConfig cfg = ctx.getSourceConfig();
            boolean minerConfigured = cfg.getMinerUsername() != null
                    && !cfg.getMinerUsername().isBlank()
                    && cfg.getMinerPassword() != null && !cfg.getMinerPassword().isBlank();
            miningPlan = decideMiningPlan(cdb, ownConId, minerConfigured);
            if (Boolean.TRUE.equals(cdb) && ownConId == 1) {
                log.warn("Project {}: connected directly to CDB$ROOT — only root-container"
                        + " objects are captured; to mine a PDB, configure its service plus"
                        + " the mining account", projectId);
            }
            if (miningPlan == MiningPlan.ROOT) {
                miningCdbName = OracleCdbSupport.cdbName(conn);
                if (miningCdbName == null) {
                    throw new SQLException("ORA-65040: source is a CDB but V$DATABASE.NAME"
                            + " is unreadable — grant SELECT ON V_$DATABASE to the source"
                            + " account so the CDB$ROOT service can be derived");
                }
                log.info("Project {}: CDB detected — mining via CDB$ROOT service '{}'"
                        + " as '{}', capturing container CON_ID {}", projectId,
                        miningCdbName, cfg.getMinerUsername(), ownConId);
            }
        }
        if (miningPlan == MiningPlan.ROOT) {
            Connection mining = dataSourceManager.openOracleRootConnection(
                    ctx.getSourceConfig(), miningCdbName);
            currentMining = mining;
            return mining;
        }
        return conn;
    }

    /**
     * Drain one session: accumulate rows per transaction key (CSCN) and flush on CSCN change
     * (target commit → lease renew → advanceScn). Empty drain means the session found nothing
     * past lastScn — the caller idles.
     */
    private List<LogMinerSession.Row> drain(LogMinerSession session) throws Exception {
        try {
            return drainSession(session);
        } finally {
            // A half transaction is re-mined next session; its spilled files have no
            // reader left.
            resetSpiller();
        }
    }

    private List<LogMinerSession.Row> drainSession(LogMinerSession session) throws Exception {
        long projectId = project.getId();
        long currentCsn = -1L;
        Map<CdcTableBinding, List<Map<String, Object>>> upserts = new LinkedHashMap<>();
        Map<CdcTableBinding, List<Map<String, Object>>> deletes = new LinkedHashMap<>();
        List<LogMinerSession.Row> all = new ArrayList<>();
        while (running && !Thread.currentThread().isInterrupted()) {
            List<LogMinerSession.Row> batch = session.nextBatch();
            if (batch.isEmpty()) {
                break;
            }
            for (LogMinerSession.Row row : batch) {
                // CDB isolation: on a CDB mined from CDB$ROOT, rows from other PDBs carry
                // a different SRC_CON_ID. Drop them here so cross-PDB same-name tables
                // cannot leak through the owner+table match below. On non-CDB ownConId=0
                // and every row passes (SRC_CON_ID is NULL/0 in V$LOGMNR_CONTENTS).
                if (ownConId > 0 && row.srcConId > 0 && row.srcConId != ownConId) {
                    continue;
                }
                if (!"DDL".equals(row.operation)
                        && !"INSERT".equals(row.operation)
                        && !"UPDATE".equals(row.operation)
                        && !"DELETE".equals(row.operation)) {
                    continue;
                }
                if (!matchesProjectTable(row.segOwner, row.tableName)) {
                    continue;
                }
                if ("DDL".equals(row.operation)) {
                    // L5: matchesProjectTable gate above means only DDL on tracked tables
                    // reaches here — previously DDL on unrelated tables flooded the WARN
                    // log and invalidated caches.
                    stats.addSkippedDdl(1);
                    String sql = row.sqlRedo == null ? "" : row.sqlRedo;
                    log.warn("Skipping DDL in LogMiner stream ({}): {}", row.segOwner,
                            sql.length() > DDL_LOG_SQL_CHARS
                                    ? sql.substring(0, DDL_LOG_SQL_CHARS) + "..." : sql);
                    supplier.invalidate(row.segOwner, row.tableName);
                    continue;
                }
                CdcTableBinding binding = supplier.binding(row.segOwner, row.tableName);
                if (binding == null || !binding.isApplicable()) {
                    stats.addUnsupportedTable(row.segOwner + "." + row.tableName);
                    continue;
                }
                SqlRedoParser.Op op;
                switch (row.operation) {
                    case "INSERT": op = SqlRedoParser.Op.INSERT; break;
                    case "UPDATE": op = SqlRedoParser.Op.UPDATE; break;
                    default: op = SqlRedoParser.Op.DELETE; break;
                }
                // H1 fix: seed currentCsn from EVERY row BEFORE parsing. Previously the
                // seed was set only after a successful parse, so a poison first row of a
                // transaction (parse failure → continue) left currentCsn=-1; the second
                // row of the SAME transaction then became the seed, and the third row
                // triggered a CSN change → flush dropped rows 2..n only, losing row 1
                // forever (retry re-reads the same archived log and skips it again).
                if (currentCsn < 0) {
                    currentCsn = row.csn;
                } else if (row.csn != currentCsn) {
                    flushTransaction(projectId, currentCsn, upserts, deletes);
                    upserts.clear();
                    deletes.clear();
                    resetSpiller();
                    currentCsn = row.csn;
                }
                SqlRedoParser.ParsedChange parsed = parser.parse(op, row.sqlRedo, binding);
                if (parsed.op == null) {
                    boolean missingColumns = !parsed.missingColumns.isEmpty();
                    if (missingColumns) {
                        // Out-of-line LOBs are structural: every change to the table will hit
                        // this. Report them as their own class with a throttled warning, so a
                        // hot table cannot flood the log and the operator still sees the drift.
                        String tableKey = row.segOwner + "." + row.tableName;
                        CdcRuntimeStats.MissingLobHit hit = stats.recordMissingLob(tableKey,
                                properties.getLob().getMetricsLogIntervalMs());
                        if (hit.warnDue()) {
                            log.warn("{} change(s) on {} skipped so far: the redo text carries"
                                    + " no value for column(s) {} (out-of-line LOB — LogMiner"
                                    + " records the row without the LOB content). The target"
                                    + " row keeps its previous version; run a full sync of the"
                                    + " table (policy {}={}) to catch up.",
                                    hit.count(), tableKey,
                                    String.join(", ", parsed.missingColumns),
                                    MISSING_LOB_POLICY_KEY, missingLobPolicy());
                        }
                        if (missingLobPolicyFail()) {
                            // Fail loudly at the first such change and never advance the
                            // position past it: the stream is now permanently incomplete.
                            parseFailures.set(MAX_PARSE_FAILURES + 1);
                            // The policy key leads the message: the park detail is
                            // truncated downstream, and this is the part users search for.
                            throw new IllegalStateException(MISSING_LOB_POLICY_KEY + "=fail: "
                                    + tableKey + " change carries no value for column(s) "
                                    + String.join(", ", parsed.missingColumns)
                                    + " (out-of-line LOB). Applying it would NULL those"
                                    + " target columns, so the project is parked here and"
                                    + " the position is not advanced; run a full sync of "
                                    + tableKey + " and set the policy back to skip to continue.");
                        }
                    }
                    parseFailures.incrementAndGet();
                    stats.addSkippedUnsupported(1);
                    if (missingColumns) {
                        // The throttled warning above already names the table and the tally.
                        log.debug("Skipping {} row on {}.{}: {}", row.operation,
                                row.segOwner, row.tableName, parsed.unsupportedReason);
                    } else {
                        log.warn("Skipping {} row on {}.{}: {} (sample: {})", row.operation,
                                row.segOwner, row.tableName, parsed.unsupportedReason,
                                truncate(row.sqlRedo, 120));
                    }
                    continue;
                }
                // H3 fix: a successfully parsed row resets the consecutive-failure counter.
                // The javadoc on MAX_PARSE_FAILURES says "5 CONSECUTIVE poison rows";
                // previously the counter only cleared at flushTransaction, so a single
                // clean row between poison batches could not save a project from
                // strike-out parking.
                parseFailures.set(0);
                if (op == SqlRedoParser.Op.DELETE) {
                    // Deletes bind primary keys only; no reason to write their payload.
                    deletes.computeIfAbsent(binding, k -> new ArrayList<>()).add(parsed.row);
                } else {
                    upserts.computeIfAbsent(binding, k -> new ArrayList<>())
                            .add(spiller().spill(binding, parsed.row));
                }
                if (row.rowTime != null) {
                    stats.touchEvent(row.rowTime.getTime());
                }
                all.add(row);
            }
        }
        if (currentCsn >= 0 && (!upserts.isEmpty() || !deletes.isEmpty())) {
            flushTransaction(projectId, currentCsn, upserts, deletes);
        }
        return all;
    }

    /**
     * Commit the buffered transaction on the target, renew the lease, then advance the SCN
     * watermark — data-first at-least-once: a crash between commit and advanceScn replays
     * the same CSCN against idempotent upserts.
     */
    private void flushTransaction(long projectId, long csn,
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
            try { target.rollback(); } catch (java.sql.SQLException ignored) { }
            throw e;
        } finally {
            try {
                if (originalAutoCommit) target.setAutoCommit(true);
            } catch (java.sql.SQLException ignored) { }
            try { target.close(); } catch (java.sql.SQLException ignored) { }
        }
        ctx.requireLease();
        positionStore.advanceScn(projectId, csn);
        stats.recordPositionScn(csn);
        parseFailures.set(0);
        // F-12: a transaction genuinely landed — any earlier failed cycles are old news
        // and must not park the project later.
        applyFailures.set(0);
    }

    /** This transaction's spiller, disposed at the CSN boundary. */
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

    private boolean matchesProjectTable(String owner, String table) {
        if (owner == null || table == null) {
            return false;
        }
        // Schema gate: two schemas with same-named tables must not both flow into CDC.
        // Oracle delivers SEG_OWNER in uppercase; the connection's source schema is
        // configured by the user and may have been entered in any case.
        String expected = ctx.getSourceSchema();
        if (expected != null && !expected.isBlank()
                && !owner.equalsIgnoreCase(expected)) {
            return false;
        }
        return ctx.getConfig().includesTable(table);
    }

    /** Configured policy; anything unrecognised is read as the default, {@code skip}. */
    private String missingLobPolicy() {
        String policy = properties.getCdc().getLogminer().getMissingLobPolicy();
        return policy == null || policy.isBlank() ? "skip" : policy.trim();
    }

    private boolean missingLobPolicyFail() {
        return "fail".equalsIgnoreCase(missingLobPolicy());
    }

    private boolean parseStrikeOut() {
        return parseFailures.get() > MAX_PARSE_FAILURES;
    }

    private boolean applyStrikeOut() {
        return applyFailures.get() > MAX_APPLY_FAILURES;
    }

    private void fatalStop(long projectId, Throwable failure,
                           CdcFailureClassifier.Kind kind) {
        String label;
        if (kind != CdcFailureClassifier.Kind.TRANSIENT) {
            label = kind.name();
        } else if (applyStrikeOut()) {
            // F-12: checked before parse — a poison-row-only project keeps its old label.
            label = "APPLY_STRIKE_OUT";
        } else {
            label = "PARSE_STRIKE_OUT";
        }
        String detail = label + ": " + (failure == null
                ? "repeated LogMiner failures"
                : CdcFailureClassifier.describe(failure));
        stats.setLastError(detail);
        taskStore.recordCdcFailure(projectId, detail);
        stateWriter.recordLog(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.ERROR, detail, false, 0L);
        log.error("LogMiner consumer for project {} parking in ERROR — {}", projectId, detail);
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
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // best-effort: pooled connection return + END_LOGMNR are both benign failures
            }
        }
    }

    @Override
    public void requestStop() {
        running = false;
        LogMinerSession session = currentSession;
        if (session != null) {
            session.close();
        }
    }

    /**
     * Per-table binding cache (mirrors {@code CdcConsumer.BindingSupplier}). Oracle names
     * are upper-case by default; {@code MetadataReader.readTable} is called with the
     * UPPER-case spelling so the generic reader's exact-name filter matches.
     */
    private final class BindingSupplier {
        /**
         * Marker for "this table cannot be applied". Cached like a binding: without it every
         * row event of a no-PK/hot table re-opens a source connection, re-reads metadata and
         * logs the same WARN — the exact storm SqlServerCdcConsumer's sentinel prevents. A
         * DDL event on the table evicts the marker, so a later PK addition self-heals.
         */
        private final Object unmappable = new Object();
        private final Map<String, Object> cache =
                Collections.synchronizedMap(new LinkedHashMap<>());

        CdcTableBinding binding(String owner, String table) {
            String key = (owner + "." + table).toUpperCase(Locale.ROOT);
            Object cached = cache.get(key);
            if (cached != null) {
                return cached == unmappable ? null : (CdcTableBinding) cached;
            }
            try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
                MetadataReader reader = ctx.getSourceReader();
                TableMeta meta = reader.readTable(conn,
                        owner.toUpperCase(Locale.ROOT), table.toUpperCase(Locale.ROOT));
                if (meta == null || !meta.hasPrimaryKey()) {
                    log.warn("LogMiner cannot map source table {}.{} (missing or no PK);"
                            + " skipping its row events", owner, table);
                    cache.put(key, unmappable);
                    return null;
                }
                CdcTableBinding b = CdcTableBinding.of(owner.toUpperCase(Locale.ROOT),
                        table.toUpperCase(Locale.ROOT), meta,
                        ctx.getConfig().targetTableName(meta.getName()),
                        Collections.emptyMap());
                cache.put(key, b);
                return b;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("LogMiner metadata read failed for "
                        + owner + "." + table, e);
            }
        }

        /** Drop the cached binding so the next DML on this table re-reads source columns. */
        void invalidate(String owner, String table) {
            String key = (owner + "." + table).toUpperCase(Locale.ROOT);
            Object removed = cache.remove(key);
            if (removed != null) {
                log.info("Invalidated LogMiner binding for {}.{} after DDL event",
                        owner, table);
            }
        }
    }
}
