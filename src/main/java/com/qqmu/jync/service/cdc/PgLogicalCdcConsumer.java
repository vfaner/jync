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
                    applyFailures.incrementAndGet();
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
        } catch (RuntimeException | Error | SQLException e) {
            // SQLException 只可能来自基线前的 ensureSlot 短连接(F-8):建槽失败=无法安全
            // 基线,与 runBaseline 读位点失败同等待遇 —— park ERROR,交监督/人工重启。
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
                                advanceSlot(conn, slotName, commitLsn);
                                lastCommitLsn = commitLsn;
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
}
