package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
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
    private final SyncStateWriter stateWriter;
    private final SyncTaskStore taskStore;
    private final DataSourceManager dataSourceManager;
    private final SyncEngine engine;

    public CdcBaselineService(CdcPositionStore positionStore, BinlogClientFactory clientFactory,
                              SyncStateWriter stateWriter, SyncTaskStore taskStore,
                              DataSourceManager dataSourceManager, SyncEngine engine) {
        this.positionStore = positionStore;
        this.clientFactory = clientFactory;
        this.stateWriter = stateWriter;
        this.taskStore = taskStore;
        this.dataSourceManager = dataSourceManager;
        this.engine = engine;
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
            String gtidSet = null;
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT @@GLOBAL.gtid_mode");
                String mode = rs.next() ? rs.getString(1) : "OFF";
                rs.close();
                if ("ON".equalsIgnoreCase(mode)) {
                    rs = st.executeQuery("SELECT @@GLOBAL.gtid_executed");
                    if (rs.next()) {
                        gtidSet = rs.getString(1);
                    }
                    rs.close();
                }
            }
            if (file == null || file.isBlank()) {
                throw new IllegalStateException(
                        "Could not read binary log position — is log_bin enabled?");
            }
            return new CdcPositionMark(file, pos, gtidSet);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read binlog position: " + e.getMessage(), e);
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
     * Oracle LogMiner: current SCN of the source database — the baseline anchor and the
     * resume watermark ({@code CSCN > scn} from the next mining session).
     */
    public long readCurrentScn(DatabaseConfig source) {
        try (Connection conn = dataSourceManager.getConnection(source);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CURRENT_SCN FROM V$DATABASE")) {
            if (!rs.next()) {
                throw new IllegalStateException("Could not read CURRENT_SCN from V$DATABASE");
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read Oracle SCN: " + e.getMessage(), e);
        }
    }

    public void runBaseline(SyncContext ctx, BooleanSupplier lockRenewer) {
        long projectId = ctx.getProject().getId();
        boolean logMiner = ctx.getConfig() != null
                && ctx.getConfig().getCaptureStrategyOrDefault()
                        == com.qqmu.jync.model.CaptureStrategy.LOGMINER_CDC;
        try {
            if (logMiner) {
                long scn = readCurrentScn(ctx.getSourceConfig());
                positionStore.recordBaselineStartScn(projectId, scn);
                stateWriter.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from SCN {} (LogMiner)",
                        projectId, scn);
            } else {
                CdcPositionMark mark = readCurrentPosition(ctx.getSourceConfig());
                positionStore.recordBaselineStart(projectId, mark.getFile(), mark.getPos(),
                        mark.getGtidSet(), clientFactory.serverIdFor(projectId));
                stateWriter.resetProject(projectId);
                log.info("CDC baseline for project {}: full load from position {}:{}",
                        projectId, mark.getFile(), mark.getPos());
            }
            SyncResult result = engine.runCycle(ctx.toBuilder().cdcBaselineRun(true).build());
            // runCycle 把 SQLException/LockLost/RuntimeException 折进 result 而不外抛:
            // 失败的全量必须在这里转成异常,否则残缺快照会被标成 baseline_done,流从
            // 记下的位点回放,漏掉的行永久丢失(任务书 B4:全量失败整体重来)。
            if (result == null || !result.isSuccess()) {
                throw new IllegalStateException("CDC baseline full load failed: "
                        + (result == null ? "no result" : result.summary()));
            }
            positionStore.markBaselineDone(projectId);
            log.info("CDC baseline for project {} completed", projectId);
        } catch (RuntimeException | Error e) {
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
            positionStore.delete(projectId);
            taskStore.recordCdcFailure(projectId, CdcFailureClassifier.describe(e));
            throw e;
        }
    }
}
