package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.projection.redis.RedisBaselineService;
import com.qqmu.jync.service.sync.AutoSyncService;
import com.qqmu.jync.service.sync.LockLostException;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Records the position, re-seeds the target with a chunked full load, then hands off
 * to streaming (task book B4: 记位点 → 分块全量 → 回放). The overlap between the
 * snapshot and the stream is absorbed by idempotent upserts — nothing in this class
 * needs to be exactly-once.
 *
 * <p>The position row is written BEFORE the full load starts, so events that happen
 * during the load are already covered by the resume point. baseline_done flips to
 * true only after the load completes; a crash mid-baseline leaves it false and the
 * whole baseline reruns (Review Focus #4).
 */
@Slf4j
@Service
public class CdcBaselineService {

    private final CdcPositionStore positionStore;
    private final BinlogClientFactory clientFactory;
    private final SyncTaskStore taskStore;
    private final DataSourceManager dataSourceManager;
    private final SyncEngine engine;
    private final RedisBaselineService redisBaseline;
    private final AutoSyncService autoSync;

    /**
     * Fan-out runtimes of this node. When one is registered for the project, the baseline
     * loads every target through the shared runtime instead of the project's single target —
     * same anchor, same not-done/retry semantics, and no per-engine fan-out branch.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.qqmu.jync.service.fanout.FanoutRuntimeRegistry fanoutRuntimes;

    @org.springframework.beans.factory.annotation.Autowired
    public CdcBaselineService(CdcPositionStore positionStore, BinlogClientFactory clientFactory,
                              SyncTaskStore taskStore,
                              DataSourceManager dataSourceManager, SyncEngine engine,
                              RedisBaselineService redisBaseline, AutoSyncService autoSync) {
        this.positionStore = positionStore;
        this.clientFactory = clientFactory;
        this.taskStore = taskStore;
        this.dataSourceManager = dataSourceManager;
        this.engine = engine;
        this.redisBaseline = redisBaseline;
        this.autoSync = autoSync;
    }

    /** Kept for hand-built consumers/tests written before auto-sync existed. */
    public CdcBaselineService(CdcPositionStore positionStore, BinlogClientFactory clientFactory,
                              SyncTaskStore taskStore,
                              DataSourceManager dataSourceManager, SyncEngine engine,
                              RedisBaselineService redisBaseline) {
        this(positionStore, clientFactory, taskStore, dataSourceManager, engine, redisBaseline,
                null);
    }

    /** SHOW BINARY LOG STATUS (8.4+) with SHOW MASTER STATUS fallback; GTID set when gtid_mode=ON. */
    public CdcPositionMark readCurrentPosition(DatabaseConfig source) {
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String file = null;
            long pos = 4L;
            try (Statement st = conn.createStatement()) {
                ResultSet rs = firstNonEmpty(st, "SHOW BINARY LOG STATUS", "SHOW MASTER STATUS");
                if (rs != null) {
                    file = rs.getString("File");
                    pos = rs.getLong("Position");
                    rs.close();
                }
            }
            String gtidSet = readGtidSet(conn);
            if (file == null || file.isBlank()) {
                throw new IllegalStateException(
                        "Could not read binary log position — is log_bin enabled?");
            }
            return new CdcPositionMark(file, pos, gtidSet);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read binlog position: " + e.getMessage(), e);
        }
    }

    /**
     * The MySQL GTID set to resume from, or null when the server has none to offer.
     *
     * <p>GTID is an optimisation, never a requirement: the file/position pair read above is
     * a complete resume point on its own. MariaDB has no {@code gtid_mode} variable at all
     * (its GTID is a different, domain-server-sequence format that the MySQL binlog client
     * cannot consume), and several MySQL-wire products omit it too. Letting that one query
     * abort the whole baseline parked every such source before it wrote a single row — the
     * SQLException escaped to the caller's catch and became
     * "Failed to read binlog position".
     */
    private String readGtidSet(Connection conn) {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT @@GLOBAL.gtid_mode");
            String mode = rs.next() ? rs.getString(1) : "OFF";
            rs.close();
            if (!"ON".equalsIgnoreCase(mode)) {
                return null;
            }
            rs = st.executeQuery("SELECT @@GLOBAL.gtid_executed");
            String set = rs.next() ? rs.getString(1) : null;
            rs.close();
            return set == null || set.isBlank() ? null : set;
        } catch (SQLException e) {
            log.info("Source does not expose a MySQL GTID set ({}); resuming from the"
                    + " file/position pair instead", e.getMessage());
            return null;
        }
    }

    private ResultSet firstNonEmpty(Statement st, String... statements) {
        for (String sql : statements) {
            try {
                ResultSet rs = st.executeQuery(sql);
                if (rs.next()) {
                    return rs;
                }
                rs.close();
            } catch (SQLException ignored) {
                // 8.0-: SHOW BINARY LOG STATUS 不存在,落到下一个
            }
        }
        return null;
    }

    /**
     * LogMiner: current SCN of the source database — the baseline anchor and the resume
     * watermark of the next mining session.
     *
     * <p>v1.9.0: the SCN source is dialect-dependent and comes from the dialect itself
     * ({@link MiningDialect#highWaterScn}) instead of a hardcoded Oracle query. Oracle
     * reads {@code V$DATABASE.CURRENT_SCN}; DM8 has no such column (measured
     * {@code [-2111]}) and uses the newest archived file's {@code NEXT_CHANGE#} — the same
     * value the miner resumes from, so the anchor and the resume point cannot disagree
     * (docs/dm-logminer-spike.md §2.3.1).
     */
    public long readCurrentScn(DatabaseConfig source) {
        try (Connection conn = dataSourceManager.getConnection(source)) {
            MiningDialect dialect;
            try {
                dialect = MiningDialects.detect(conn);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to read the source start SCN: "
                        + e.getMessage(), e);
            }
            long scn;
            try {
                scn = dialect.highWaterScn(conn);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to read the source start SCN: "
                        + e.getMessage(), e);
            }
            if (scn == DmLogMinerDialect.NO_HIGH_WATER) {
                // Nothing archived = nothing mineable, and no anchor to start from. Retrying
                // never helps; the message names the one command that does.
                throw new IllegalStateException("The source has no archived redo yet, so the"
                        + " baseline has no start SCN: DM can only mine archived files."
                        + " Run ALTER SYSTEM SWITCH LOGFILE (or schedule a job that does it"
                        + " every minute) and retry; also confirm RLOG_APPEND_LOGIC=2 so the"
                        + " redo carries full old images.");
            }
            return scn;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the source start SCN: "
                    + e.getMessage(), e);
        }
    }

    /**
     * PG family: current WAL LSN — the baseline anchor for the logical-slot consumer. The
     * slot replay resumes from strictly beyond this point (the full-load overlaps the
     * window; idempotent upsert covers the double-write).
     *
     * <p>v1.9.0: the function name is dialect-dependent. openGauss 5.0.0 forked PG 9.2 and
     * has no {@code pg_current_wal_lsn()} at all (measured: "function does not exist");
     * it keeps {@code pg_current_xlog_location()}. Vanilla PostgreSQL keeps the v1.7.0
     * query byte for byte.
     */
    public String readCurrentLsnPg(DatabaseConfig source) {
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String sql = PgLogicalDialect.detectLive(conn).currentLsnSql();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) {
                    throw new IllegalStateException("Could not read the current WAL position ("
                            + sql + ")");
                }
                String lsn = rs.getString(1);
                if (lsn == null || lsn.isBlank()) {
                    throw new IllegalStateException(
                            "The current WAL position query returned empty (" + sql + ")");
                }
                return lsn.trim();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read PostgreSQL LSN: " + e.getMessage(), e);
        }
    }

    /** The lowest LSN SQL Server accepts; used when nothing has been captured yet. */
    static final String SQLSERVER_ZERO_LSN = "0x00000000000000000000";

    /**
     * SQL Server: max committed LSN across all capture instances
     * ({@code sys.fn_cdc_get_max_lsn()}) — the baseline anchor; read back as a hex string
     * because the JDBC driver hands it back as binary(10).
     *
     * <p>{@code fn_cdc_get_max_lsn()} reads {@code cdc.lsn_time_mapping}, which stays
     * EMPTY until the capture job has processed its first transaction. A freshly enabled
     * database therefore returns NULL even though CDC is on — verified on SQL Server 2022:
     * after {@code sp_cdc_enable_db} + {@code sp_cdc_enable_table}, before any capture run,
     * the function returns NULL. Treating that as "CDC disabled" parked the consumer in the
     * ERROR hat and demanded a manual rebaseline. Since nothing has been captured at that
     * moment, anchoring at LSN zero is equivalent to "start from now": replay cannot begin
     * before any real event, and the first captured change is applied in order.
     *
     * <p>Genuine {@code is_cdc_enabled=0} still fails — that state must not be papered over.
     */
    public String readCurrentLsnSqlServer(DatabaseConfig source) {
        try (Connection conn = dataSourceManager.getConnection(source);
             Statement st = conn.createStatement()) {
            String lsn;
            try (ResultSet rs = st.executeQuery(
                    "SELECT CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)")) {
                lsn = rs.next() ? rs.getString(1) : null;
            }
            if (lsn != null && !lsn.isBlank()) {
                return lsn.trim();
            }
            Boolean enabled = readCdcDbEnabled(st);
            if (Boolean.TRUE.equals(enabled)) {
                log.info("SQL Server CDC: fn_cdc_get_max_lsn() is NULL but the database has"
                        + " CDC enabled — no change has been captured yet; anchoring the"
                        + " baseline at {}", SQLSERVER_ZERO_LSN);
                return SQLSERVER_ZERO_LSN;
            }
            if (Boolean.FALSE.equals(enabled)) {
                throw new IllegalStateException("fn_cdc_get_max_lsn() returned NULL and"
                        + " sys.databases.is_cdc_enabled=0 — SQL Server CDC is not enabled"
                        + " on this database; run EXEC sys.sp_cdc_enable_db;");
            }
            throw new IllegalStateException(
                    "Could not read sys.fn_cdc_get_max_lsn() — is SQL Server CDC enabled?");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to read SQL Server CDC LSN: " + e.getMessage(), e);
        }
    }

    /** db-level CDC flag, or null when undeterminable (old server, missing permission). */
    private static Boolean readCdcDbEnabled(Statement st) {
        try (ResultSet rs = st.executeQuery(
                "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()")) {
            return rs.next() ? rs.getBoolean(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Records the capture strategy's current position as the baseline anchor and returns
     * the LogMiner SCN (null for every other strategy) so the caller can re-check the
     * archive floor once the load finishes. Exposed through {@link CdcBaselineAnchor} so
     * the Redis baseline can record the same anchor without a service cycle.
     */
    public Long recordBaselineAnchor(SyncContext ctx) {
        long projectId = ctx.getProject().getId();
        // Hand-built contexts (tests, the Redis baseline's callers) may carry no config at
        // all; they behaved like POLLING before this method existed, and still do.
        CaptureStrategy strategy = ctx.getConfig() == null
                ? CaptureStrategy.POLLING
                : ctx.getConfig().getCaptureStrategyOrDefault();
        // A baseline that failed halfway leaves its position row not-done. Resuming it must
        // keep that anchor and the chunk checkpoints: replacing the anchor would skip every
        // change between the old and new position, and resetting progress would throw away
        // the completed tables' resume state. A deliberate rebuild deletes the position
        // first, so this branch is only the "continue unfinished baseline" path.
        if (ctx.getProject().getTargetKindOrDefault() != SyncTargetKind.REDIS) {
            CdcPosition existing = positionStore.find(projectId).orElse(null);
            if (existing != null && !Boolean.TRUE.equals(existing.isBaselineDone())) {
                log.info("CDC baseline for project {} resumes from its existing anchor;"
                        + " completed tables and chunk checkpoints are kept", projectId);
                return existing.getScn();
            }
        }
        Long logMinerAnchorScn = null;
        switch (strategy) {
            case LOGMINER_CDC: {
                long scn = readCurrentScn(ctx.getSourceConfig());
                logMinerAnchorScn = scn;
                positionStore.recordBaselineStartScn(projectId, scn);
                engine.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from SCN {} (LogMiner)",
                        projectId, scn);
                break;
            }
            case PG_LOGICAL_CDC: {
                String lsn = readCurrentLsnPg(ctx.getSourceConfig());
                positionStore.recordBaselineStartLsn(projectId, lsn);
                engine.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from LSN {} (PG logical)",
                        projectId, lsn);
                break;
            }
            case SQLSERVER_CDC: {
                String lsn = readCurrentLsnSqlServer(ctx.getSourceConfig());
                positionStore.recordBaselineStartLsn(projectId, lsn);
                engine.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from LSN {} (SQL Server CDC)",
                        projectId, lsn);
                break;
            }
            case YSTREAM_CDC: {
                // 崖山没有 SHOW MASTER STATUS,锚点也不是这里读的:YStreamServiceLifecycle
                // 创建服务时已经从 RESTART_POSITION 读出基线起点并写成 recordBaselineStartLsn。
                // 落进 default 会去读 binlog 位点,项目一开始就被 park 在 ERROR。
                engine.resetProject(projectId);
                log.info("CDC baseline for project {}: full load; YStream replay starts at the"
                        + " anchor recorded when the service was created", projectId);
                break;
            }
            case POLLING:
            case FULL_COMPARE:
            case BINLOG_CDC:
            default: {
                CdcPositionMark mark = readCurrentPosition(ctx.getSourceConfig());
                positionStore.recordBaselineStart(projectId, mark.getFile(), mark.getPos(),
                        mark.getGtidSet(), clientFactory.serverIdFor(projectId));
                engine.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from position {}:{}",
                        projectId, mark.getFile(), mark.getPos());
                break;
            }
        }
        return logMinerAnchorScn;
    }

    /**
     * Adds source tables that appeared after the auto-sync switch was enabled and gives each
     * one its own full load, without resetting the CDC position or touching other tables.
     *
     * <p>The caller invokes this while no binlog client is attached (before connecting or
     * after a disconnect). The stored position is therefore the anchor: changes made during
     * the load are replayed from it afterwards, and the new tables' full load covers their
     * rows up to the load; both are idempotent upserts. A failed load rolls the additions
     * back so the next reconnect retries instead of streaming a table with missing history.
     *
     * @return true when at least one table was added and loaded
     */
    public boolean reconcileAutoSync(SyncContext ctx) throws Exception {
        CaptureStrategy strategy = ctx.getConfig() == null
                ? CaptureStrategy.POLLING : ctx.getConfig().getCaptureStrategyOrDefault();
        if (autoSync == null || ctx.getConfig() == null
                || !ctx.getConfig().isAutoSyncNewTables()
                // Engines whose stream already carries any table of the schema: binlog and
                // PG test_decoding (which decodes the whole database, no publication or
                // enablement step). SQL Server capture instances, YStream services and
                // LogMiner supplemental logging stay manual (and are reported by their own
                // probes); enabling auto-sync there would look successful while the new
                // table never streams.
                || (strategy != CaptureStrategy.BINLOG_CDC
                        && strategy != CaptureStrategy.PG_LOGICAL_CDC)
                || ctx.getProject().getTargetKindOrDefault() == SyncTargetKind.REDIS) {
            return false;
        }
        List<String> added;
        try (Connection conn = dataSourceManager.getConnection(ctx.getSourceConfig())) {
            List<String> sourceTables = ctx.getSourceReader()
                    .listTableNames(conn, ctx.getSourceSchema());
            added = autoSync.reconcile(ctx.getProject(), ctx.getConfig(), sourceTables, name -> {
                try {
                    TableMeta table = ctx.getSourceReader()
                            .readTable(conn, ctx.getSourceSchema(), name);
                    return table != null && table.hasPrimaryKey();
                } catch (SQLException e) {
                    log.debug("Auto-sync: cannot read metadata for {}: {}", name, e.getMessage());
                    return false;
                }
            });
        }
        if (added.isEmpty()) {
            return false;
        }
        try {
            SyncResult result = engine.runCycle(ctx.toBuilder()
                    .repairTables(new LinkedHashSet<>(added))
                    .cdcRepairRun(true)
                    .build());
            if (!result.isSuccess()) {
                throw new IllegalStateException("auto-sync baseline did not succeed for "
                        + added + ": " + result.getErrors());
            }
            log.info("Project '{}': auto-sync baseline loaded {} new table(s): {}",
                    ctx.projectId(), added.size(), added);
            return true;
        } catch (RuntimeException | Error e) {
            autoSync.rollback(ctx.getProject(), ctx.getConfig(), added);
            throw e;
        }
    }

    public void runBaseline(SyncContext ctx, BooleanSupplier lockRenewer) {
        long projectId = ctx.getProject().getId();
        // LogMiner only: the SCN the full load anchored at, checked against the source's
        // archive floor once the load finishes (see assertArchiveStillCovers).
        Long logMinerAnchorScn = null;
        try {
            if (ctx.getProject().getTargetKindOrDefault() == SyncTargetKind.REDIS) {
                // A cache target cannot run the JDBC full load. The Redis baseline records the
                // same anchor through this::recordBaselineAnchor and projects the rows itself;
                // it stays inside this try so a failure keeps the JDBC semantics below (clear
                // the anchor, record the ERROR hat, leave it not-done). For Redis the anchor is
                // reset rather than the row deleted, because the row also carries the contract
                // of the keys the partial baseline already wrote.
                redisBaseline.run(ctx, lockRenewer, this::recordBaselineAnchor);
                return;
            }
            logMinerAnchorScn = recordBaselineAnchor(ctx);
            com.qqmu.jync.service.fanout.FanoutRuntime fanout =
                    fanoutRuntimes == null ? null : fanoutRuntimes.get(projectId);
            if (fanout != null) {
                // A fan-out baseline reads the source once and publishes to the shared
                // transport, so every target receives the same snapshot. The anchor was
                // already recorded above, exactly as for a single target; the archive check
                // and the done marker keep their order (check, then mark) so a gap cannot be
                // shipped as a complete baseline.
                java.util.List<com.qqmu.jync.service.fanout.FanoutBaselineService.TableLoad>
                        loads = runFanoutBaseline(fanout);
                if (logMinerAnchorScn != null) {
                    assertArchiveStillCovers(ctx.getSourceConfig(), logMinerAnchorScn);
                }
                positionStore.markBaselineDone(projectId);
                if (!loads.isEmpty()) {
                    try {
                        taskStore.markCdcDataApplied(projectId);
                    } catch (RuntimeException e) {
                        log.warn("Could not advance last-sync after the fan-out baseline of"
                                + " project {}: {}", projectId, e.getMessage());
                    }
                }
                log.info("Fan-out baseline for project {} completed ({} table(s))",
                        projectId, loads.size());
                return;
            }
            SyncResult result = engine.runCycle(ctx.toBuilder().cdcBaselineRun(true).build());
            // runCycle 把 SQLException/LockLost/RuntimeException 折进 result 而不外抛:
            // 失败的全量必须在这里转成异常,否则残缺快照会被标成 baseline_done,流从
            // 记下的位点回放,漏掉的行永久丢失(任务书 B4:全量失败整体重来)。
            // A deferred round is not a failure, but it read nothing either: marking it done
            // would ship a half snapshot as complete and lose every row the stream resumes
            // past. Same ruling as a failed load — the baseline stays not-done.
            if (result == null || !result.isSuccess() || result.isDeferred()) {
                if (!lockRenewer.getAsBoolean()) {
                    // R1: the lease is gone — another node may already be running its own
                    // baseline against the same position row. Deleting the row or raising
                    // the ERROR hat here would clobber that node's fresh anchor and, via the
                    // ERROR-skip, freeze every node's supervise. This node has no right to
                    // finalise anything; exit quietly and let the owner redo the baseline.
                    throw new LockLostException("CDC baseline for project " + projectId
                            + " lost its lease mid-load; leaving the position row to the"
                            + " instance that took over");
                }
                throw new IllegalStateException("CDC baseline full load failed: "
                        + (result == null ? "no result" : result.summary()));
            }
            if (logMinerAnchorScn != null) {
                assertArchiveStillCovers(ctx.getSourceConfig(), logMinerAnchorScn);
            }
            positionStore.markBaselineDone(projectId);
            // The baseline just re-copied the source: rows landed in the target, so 上次同步
            // must move even when the stream afterwards sees no further changes. The change
            // window logger only stamps the streaming phase, which left a freshly
            // re-baselined project showing the timestamp of its last CDC change window —
            // days old, right next to a position that had just been written. Only the
            // timestamp: status and the result summary belong to the consumer's lifecycle
            // (see SyncTaskStore.markCdcDataApplied).
            if (result.hasChanges()) {
                try {
                    taskStore.markCdcDataApplied(projectId);
                } catch (RuntimeException e) {
                    log.warn("Could not advance last-sync after the CDC baseline of project"
                            + " {}: {}", projectId, e.getMessage());
                }
            }
            log.info("CDC baseline for project {} completed", projectId);
        } catch (RuntimeException | Error e) {
            if (e instanceof LockLostException) {
                log.info("CDC baseline for project {} stopped: lease lost (another node may"
                        + " have taken over); it stays not-done and the owner reruns it",
                        projectId);
                throw e;
            }
            if (Thread.currentThread().isInterrupted()) {
                // Stop-requested abort (final review FIX 2): a disable/rebaseline/shutdown
                // interrupted the chunked load, which left the flag set on its way out.
                // NOT a failure — no ERROR hat (recordCdcFailure would park the project
                // across every node's supervise) and no position delete: the row keeps
                // baseline_done=false, so the baseline stays NOT done and reruns in full on
                // the next start. Skipping the delete also protects the racing
                // rebaseline-during-first-baseline case, where a NEW consumer's fresh
                // baseline-start row must not be removed by the old thread's exit.
                log.info("CDC baseline for project {} aborted by a stop request; it stays"
                        + " not-done and reruns on the next start ({})", projectId,
                        e.toString());
                throw e;
            }
            if (!lockRenewer.getAsBoolean()) {
                // The lease is gone, so another node may already own the position row — the
                // same ruling as the failed-result path above. Deleting the row (or raising
                // the ERROR hat, which freezes every node's supervise) would clobber that
                // node's fresh anchor. Exit as a lease loss and let the owner redo it.
                log.info("CDC baseline for project {} failed after losing its lease ({});"
                        + " leaving the position row to the instance that took over",
                        projectId, e.toString());
                throw new LockLostException("CDC baseline for project " + projectId
                        + " lost its lease mid-load: " + e);
            }
            if (ctx.getProject().getTargetKindOrDefault() == SyncTargetKind.REDIS) {
                // A cache baseline that failed halfway has already written some keys. Their
                // value format is recorded on the position row, and deleting the row would
                // lose it: the rerun's baseline could write a different format over the
                // leftovers and answer WRONGTYPE. Reset the anchor (so the baseline still
                // reruns), keep the contract until a completed baseline replaces it.
                positionStore.resetAnchorKeepingProjectionSignature(projectId);
            } else {
                // Keep the not-done row: it carries the original anchor. The next start or
                // "continue baseline" reuses it together with the chunk checkpoints, so a
                // failed 500M-row table does not force every completed table to reload. A
                // deliberate rebuild deletes this row first and starts from a fresh anchor.
                log.info("CDC baseline for project {} failed; keeping its anchor and chunk"
                        + " checkpoints for resume (use rebuild for a fresh anchor)", projectId);
            }
            String detail = CdcFailureClassifier.describe(e);
            if (CdcFailureClassifier.isTransient(e)) {
                // A blip during a multi-hour baseline must not become the permanent ERROR
                // hat; the anchor and chunk checkpoints stay, so the next start resumes.
                log.warn("CDC baseline for project {} hit a transient failure; keeping the"
                        + " anchor for a retry: {}", projectId, detail);
            } else {
                taskStore.recordCdcFailure(projectId, detail);
            }
            throw e;
        }
    }

    /**
     * The fan-out half of {@link #runBaseline}: the runtime creates every target's tables,
     * reads the source once and waits for each target to acknowledge the snapshot. Failures
     * propagate as unchecked exceptions so the caller's keep-the-anchor / transient-retry
     * handling stays identical to the single-target path.
     */
    private java.util.List<com.qqmu.jync.service.fanout.FanoutBaselineService.TableLoad>
            runFanoutBaseline(com.qqmu.jync.service.fanout.FanoutRuntime fanout) {
        try {
            return fanout.baseline();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Fan-out baseline failed: " + e.getMessage(), e);
        }
    }

    /**
     * A LogMiner baseline anchors at CURRENT_SCN before the full load and only starts
     * mining after it. Everything written in between has to still be in the archived redo
     * at that moment, so a full load that outlasts the source's archive retention leaves a
     * hole that can never be mined — the first START_LOGMNR then dies on a bare ORA-01291
     * that names no SCN and no cause.
     *
     * <p>Checking here turns that into one actionable message at the moment it happens:
     * the anchor, the source's current redo floor, and the remedy. Only a DEFINITE gap
     * fails the baseline (floor strictly above the anchor); an unreadable floor — no
     * privilege on V$ views, a PDB connection, a DM source — is just a missing diagnostic
     * and must not undo a full load that already completed.
     */
    void assertArchiveStillCovers(DatabaseConfig source, long anchorScn) {
        Long floor;
        try (Connection conn = dataSourceManager.getConnection(source)) {
            floor = MiningDialects.detect(conn).oldestMineableScn(conn);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not read the source's oldest mineable SCN for project's LogMiner"
                    + " baseline anchor {} ({}); the first mining session will check again",
                    anchorScn, e.getMessage());
            return;
        }
        if (floor != null && floor > anchorScn) {
            throw new IllegalStateException("The full load finished at SCN " + anchorScn
                    + " but the oldest redo still on the source now starts at SCN " + floor
                    + " — the archive stream was purged while the baseline ran, so the"
                    + " changes made after the anchor can never be mined and the stream"
                    + " cannot resume. Increase the source's archive retention (FRA space,"
                    + " RMAN ARCHIVELOG DELETION POLICY) so it covers the whole full-load"
                    + " window, then rebuild the baseline.");
        }
    }
}
