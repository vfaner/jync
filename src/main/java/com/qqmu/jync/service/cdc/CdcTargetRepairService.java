package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.util.TargetTableVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * 目标表被删后的 CDC 定向修复:重建并重灌缺失的那几张表,从原 CDC 位点继续回放。
 *
 * <p>轮询任务一直会在目标表消失时幂等重建它({@code SyncEngine.ensureTargetTableExists}),
 * 用户也因此期望 CDC 有同样的行为;此前 CDC 只把"目标表不存在"当成瞬时故障,退避重连、
 * 重放同一笔事务、再次失败,面板上只有一个"已断开"。本服务把发生故障的那张表(以及本轮
 * 盘点出的其他缺失表)交给引擎跑一次受限周期:只读这些表、只建这些表、只全量重灌这些表,
 * 位点完全不动。
 *
 * <p><b>为什么不是基线重跑:</b>项目级重新基线会清空每一张表的游标并重灌全部已选表。一张
 * 表被删就把整个项目重灌一遍,与用户"和轮询一致"的预期不符,代价也完全不同。定向修复
 * 只碰缺失的表;名单之外的表、视图、存储过程、结构差分、删除检测都不在本轮之内(见
 * {@link SyncEngine} 对 {@code cdcRepairRun} 的处理)。
 *
 * <p><b>上限:</b>连续修复轮次按项目计数,任何一笔流式事务成功提交后清零。达到上限说明
 * 删表的原因仍在持续(触发器、权限、被反复重放的 DDL),继续重建只会掩盖问题,所以交给
 * 人工处理。
 */
@Slf4j
@Service
public class CdcTargetRepairService {

    /**
     * 变更日志里修复条目的固定前缀。告警收集器按它把"修复成功"变成一次醒目事件,而不是
     * 一句埋在日志里的 INFO。
     */
    public static final String REPAIR_LOG_MARKER = "cdc target table repair: ";

    public enum Status {
        /** 缺失的表已重建并重灌,流可以从原位点继续回放。 */
        REPAIRED,
        /** 修复已执行,但目标仍缺表,或引擎报告了错误。 */
        FAILED,
        /** 连续修复次数达到上限,必须人工介入。 */
        CAP_REACHED,
        /** 配置里关闭了自动修复。 */
        DISABLED,
        /** 不是"目标表缺失",或项目形态不支持修复(Redis 目标、未开启数据同步等)。 */
        NOT_APPLICABLE
    }

    public record Outcome(Status status, List<String> repairedTables, String detail) {

        public boolean repaired() {
            return status == Status.REPAIRED;
        }

        static Outcome of(Status status, List<String> repairedTables, String detail) {
            return new Outcome(status, List.copyOf(repairedTables),
                    detail == null ? "" : detail);
        }
    }

    private final DataSourceManager dataSourceManager;
    private final SyncEngine syncEngine;
    private final SyncStateWriter stateWriter;
    private final CdcDdlTaskReconciler ddlTaskReconciler;
    private final SyncProperties properties;

    /** 每个项目连续未成功提交的修复轮次;成功提交一笔事务后由调用方清零。 */
    private final ConcurrentHashMap<Long, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public CdcTargetRepairService(DataSourceManager dataSourceManager, SyncEngine syncEngine,
                                  SyncStateWriter stateWriter,
                                  CdcDdlTaskReconciler ddlTaskReconciler,
                                  SyncProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.syncEngine = syncEngine;
        this.stateWriter = stateWriter;
        this.ddlTaskReconciler = ddlTaskReconciler;
        this.properties = properties;
    }

    /**
     * Handles one stream failure. Never throws: every failure mode is an {@link Outcome} the
     * consumer can turn into either "continue replaying" or a parked, actionable ERROR.
     */
    public Outcome repair(SyncContext ctx, Throwable failure) {
        CdcTargetTableMissingException missing = findMissingTableFault(failure);
        if (missing == null
                && CdcFailureClassifier.classify(failure)
                        != CdcFailureClassifier.Kind.TARGET_TABLE_MISSING) {
            return Outcome.of(Status.NOT_APPLICABLE, List.of(),
                    "not a missing-target-table fault");
        }
        if (ctx == null || ctx.getProject() == null || ctx.getConfig() == null) {
            return Outcome.of(Status.NOT_APPLICABLE, List.of(), "no project context");
        }
        if (ctx.getProject().getTargetKindOrDefault() != SyncTargetKind.JDBC) {
            return Outcome.of(Status.NOT_APPLICABLE, List.of(),
                    "targeted repair only applies to relational targets");
        }
        if (!ctx.getConfig().isSyncData()) {
            return Outcome.of(Status.NOT_APPLICABLE, List.of(),
                    "data sync is off, so a reload could not restore any rows");
        }
        if (!properties.getCdc().isTargetTableRepairEnabled()) {
            return Outcome.of(Status.DISABLED, List.of(),
                    "targeted repair is disabled (sync.cdc.target-table-repair-enabled=false)");
        }

        int maxAttempts = Math.max(1, properties.getCdc().getTargetTableRepairMaxAttempts());
        long projectId = ctx.projectId();
        AtomicInteger used = attempts.computeIfAbsent(projectId, id -> new AtomicInteger());
        if (used.get() >= maxAttempts) {
            return Outcome.of(Status.CAP_REACHED, List.of(),
                    "the target table was missing in " + used.get() + " consecutive repair"
                            + " cycles (limit " + maxAttempts + "), so the cause is still"
                            + " present; fix it, then restart the project or re-baseline");
        }

        List<String> repairTables;
        try {
            repairTables = collectMissingTables(ctx, missing);
        } catch (SQLException e) {
            String detail = "could not enumerate the target tables: " + e.getMessage();
            log.error("Project {}: {}", projectId, detail);
            return Outcome.of(Status.FAILED, List.of(), detail);
        }
        if (repairTables.isEmpty()) {
            return Outcome.of(Status.NOT_APPLICABLE, List.of(),
                    "the source no longer has the table named by the fault");
        }

        used.incrementAndGet();
        log.warn("Project {}: target table missing during CDC replay; recreating and reloading"
                + " {} table(s) without moving the CDC position: {}", projectId,
                repairTables.size(), repairTables);
        SyncResult result = syncEngine.runCycle(ctx.toBuilder()
                .repairTables(new LinkedHashSet<>(repairTables))
                .cdcRepairRun(true)
                .build());

        if (!result.isSuccess()) {
            String detail = result.getErrors().isEmpty()
                    ? "the repair cycle did not succeed" : String.join("; ", result.getErrors());
            log.error("Project {}: targeted repair failed: {}", projectId, detail);
            return Outcome.of(Status.FAILED, repairTables, detail);
        }

        // 引擎报告成功不等于表回来了(例如目标把 CREATE 降级成 skip,或周期只做了一部分
        // 就提前返回)。表在,才允许消费者回到回放;不在就把原因交给人工。
        List<String> stillMissing = missingTargetTables(ctx, repairTables);
        if (!stillMissing.isEmpty()) {
            String detail = "the target still lacks " + stillMissing
                    + " after the repair cycle";
            log.error("Project {}: {}", projectId, detail);
            return Outcome.of(Status.FAILED, repairTables, detail);
        }

        // The rebuilt table now carries the source's current structure, and the reload closed
        // the data gap: every skipped-DDL task on these tables describes work this cycle has
        // already done. Leave them open and the log keeps asking for an ALTER whose column
        // already exists, while the column projection stays forced on across restarts.
        int closedTasks;
        try {
            closedTasks = ddlTaskReconciler.resolveAfterTargetRebuild(projectId, repairTables);
        } catch (RuntimeException e) {
            // Closing is bookkeeping; failing it must not turn a successful repair into an
            // outage. The tasks simply stay pending, exactly as they were.
            log.warn("Project {}: repaired the target table(s) but could not close their open"
                    + " DDL task(s): {}", projectId, e.toString());
            closedTasks = 0;
        }

        recordRepair(ctx, repairTables, result.getRowsInserted(), closedTasks);
        return Outcome.of(Status.REPAIRED, repairTables,
                "recreated and reloaded " + repairTables);
    }

    /** 一笔流式事务成功提交:本次故障已经过去,下一次删表应当重新获得完整预算。 */
    public void resetAttempts(Long projectId) {
        if (projectId != null) {
            attempts.remove(projectId);
        }
    }

    /**
     * The tables this cycle should rebuild: every selected source table whose target is absent,
     * plus the one the fault named — always. The named table may have been recreated (empty) by
     * someone else between the failure and this call, and its rows are still missing, so it is
     * reloaded regardless of the probe.
     */
    private List<String> collectMissingTables(SyncContext ctx,
                                              CdcTargetTableMissingException fault)
            throws SQLException {
        Set<String> missing = new LinkedHashSet<>();
        try (Connection source = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection target = dataSourceManager.getConnection(ctx.getTargetConfig())) {
            List<String> sourceTables = ctx.getSourceReader()
                    .listTableNames(source, ctx.getSourceSchema());
            boolean faultTableWasListed = false;
            for (String sourceTable : sourceTables) {
                if (fault != null && sourceTable.equalsIgnoreCase(fault.sourceTable())) {
                    faultTableWasListed = true;
                }
                if (!ctx.getConfig().includesTable(sourceTable)) {
                    continue;
                }
                String targetTable = ctx.getConfig().targetTableName(sourceTable);
                if (!TargetTableVerifier.tableExists(target, ctx.getTargetSchema(), targetTable,
                        ctx.effectiveTargetType())) {
                    missing.add(sourceTable);
                }
            }
            if (fault != null && !faultTableWasListed) {
                // 源表已经不存在:没有东西可灌,修复无从谈起。
                return List.of();
            }
            if (fault != null && faultTableWasListed) {
                missing.add(sourceTableNamed(sourceTables, fault.sourceTable()));
            }
        }
        return new ArrayList<>(missing);
    }

    private List<String> missingTargetTables(SyncContext ctx, List<String> sourceTables) {
        List<String> stillMissing = new ArrayList<>();
        try (Connection target = dataSourceManager.getConnection(ctx.getTargetConfig())) {
            for (String sourceTable : sourceTables) {
                String targetTable = ctx.getConfig().targetTableName(sourceTable);
                if (!TargetTableVerifier.tableExists(target, ctx.getTargetSchema(), targetTable,
                        ctx.effectiveTargetType())) {
                    stillMissing.add(targetTable);
                }
            }
        } catch (SQLException e) {
            log.debug("Could not re-check repaired target tables: {}", e.getMessage());
            // 验证不了就不宣称成功,也不宣称失败之外的表已到位:交给消费者按失败处理。
            return sourceTables;
        }
        return stillMissing;
    }

    private static String sourceTableNamed(List<String> sourceTables, String name) {
        for (String candidate : sourceTables) {
            if (candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return name;
    }

    private void recordRepair(SyncContext ctx, List<String> sourceTables, long rows,
                              int closedTasks) {
        String closed = closedTasks == 0 ? ""
                : "; closed " + closedTasks + " open skipped-DDL task(s) as completed by"
                        + " the rebuild";
        for (String sourceTable : sourceTables) {
            String targetTable = ctx.getConfig().targetTableName(sourceTable);
            String detail = REPAIR_LOG_MARKER + "recreated target table "
                    + (targetTable == null ? sourceTable : targetTable)
                    + " and reloaded it from the source (" + rows
                    + " row(s) written); the CDC position was left untouched, so replay"
                    + " continues from the same point" + closed;
            stateWriter.recordLog(ctx.projectId(), ObjectType.TABLE, sourceTable,
                    ChangeType.INFO, detail, true, 0L, (int) Math.min(Integer.MAX_VALUE, rows));
        }
    }

    /** The sentinel may arrive wrapped by a driver or an engine-level wrapper. */
    private static CdcTargetTableMissingException findMissingTableFault(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CdcTargetTableMissingException missing) {
                return missing;
            }
        }
        return null;
    }
}
