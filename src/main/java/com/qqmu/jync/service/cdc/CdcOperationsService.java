package com.qqmu.jync.service.cdc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * On-demand CDC operations against an existing project (task book v1.5.0): the per-project
 * capability probe (Task 16) and the one-click "rebuild baseline" recovery (Task 17, B7).
 *
 * <p>{@link #probeProject} never throws for a merely-unready or unreachable source — it
 * degrades to a failed {@link CdcProbeResult}, matching {@code BinlogCdcProbe} and
 * {@code ConnectionTestService.test}: a broken probe must neither block the detail page
 * nor the save flow (B2).
 */
@Service
@Slf4j
public class CdcOperationsService {

    /**
     * Own mapper for {@link #parseStrategy}, configured exactly like the ones in
     * CdcConsumerManager and ProjectService so all three reads of sync_config agree; a
     * skew would let rebaseline reject (or worse, accept) the wrong projects. The manager
     * bean cannot be reused here because rebaseline's "not a CDC project" refusal must
     * happen with ZERO manager interaction (Task 17 test contract), which a
     * {@code cdcManager.strategyOf} call would break under unit-test mocks.
     */
    private static final ObjectMapper STRATEGY_JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final BinlogCdcProbe probe;
    private final LogMinerCdcProbe logMinerProbe;
    private final PgLogicalReplicationCdcProbe pgProbe;
    private final BinlogClientFactory clientFactory;
    private final CdcConsumerManager cdcManager;
    private final CdcPositionStore positionStore;
    private final SyncStateWriter stateWriter;
    private final SyncTaskStore taskStore;
    private final AdminAuditService auditService;
    private final ChangeLogRepository changeLogRepository;
    private final SyncLockService lockService;

    public CdcOperationsService(ProjectRepository projectRepository,
                                DatabaseConfigRepository databaseConfigRepository,
                                DataSourceManager dataSourceManager,
                                BinlogCdcProbe probe,
                                LogMinerCdcProbe logMinerProbe,
                                PgLogicalReplicationCdcProbe pgProbe,
                                BinlogClientFactory clientFactory,
                                CdcConsumerManager cdcManager,
                                CdcPositionStore positionStore,
                                SyncStateWriter stateWriter,
                                SyncTaskStore taskStore,
                                AdminAuditService auditService,
                                ChangeLogRepository changeLogRepository,
                                SyncLockService lockService) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.dataSourceManager = dataSourceManager;
        this.probe = probe;
        this.logMinerProbe = logMinerProbe;
        this.pgProbe = pgProbe;
        this.clientFactory = clientFactory;
        this.cdcManager = cdcManager;
        this.positionStore = positionStore;
        this.stateWriter = stateWriter;
        this.taskStore = taskStore;
        this.auditService = auditService;
        this.changeLogRepository = changeLogRepository;
        this.lockService = lockService;
    }

    /**
     * Runs the itemized B2 capability probe against a project's real source, presenting
     * the same server id the consumer would later use ({@link BinlogClientFactory#serverIdFor}),
     * so a collision shows up here rather than at connect time.
     *
     * <p>Dispatches by source type: MySQL → binlog probe (existing, unchanged); Oracle →
     * LogMiner probe (v1.6.0); anything else → 400 {@code cdc.probe.unsupported}.
     *
     * @throws IllegalArgumentException with the bare key {@code cdc.probe.unsupported} when
     *         the project's source is missing or neither MySQL nor Oracle (the API
     *         controller turns that into a 400)
     */
    public CdcProbeResult probeProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        DatabaseConfig source = project.getSourceDbId() == null ? null
                : databaseConfigRepository.findById(project.getSourceDbId()).orElse(null);
        if (source == null || source.getType() == null
                || (source.getType() != DatabaseType.MYSQL
                        && source.getType() != DatabaseType.ORACLE
                        && source.getType() != DatabaseType.POSTGRESQL)) {
            throw new IllegalArgumentException("cdc.probe.unsupported");
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            if (source.getType() == DatabaseType.MYSQL) {
                long plannedServerId = clientFactory.serverIdFor(projectId);
                return probe.probe(conn, plannedServerId);
            }
            if (source.getType() == DatabaseType.POSTGRESQL) {
                // Table-level REPLICA IDENTITY check requires the project's table list;
                // deferred to a later iteration — here we run the database-level probe.
                return pgProbe.probe(conn, List.of());
            }
            // Connection-test-time probe has no project context — table-level checks
            // (supplemental logging per table, LONG columns) are skipped when empty.
            return logMinerProbe.probe(conn, List.of());
        } catch (SQLException | RuntimeException e) {
            // Never an exception to the caller: degrade to a failed check carrying the
            // driver's message, mirroring BinlogCdcProbe.checkVersion's own SQLException path.
            log.debug("CDC probe could not reach the source of project {}: {}",
                    projectId, e.toString());
            String anchor;
            if (source.getType() == DatabaseType.ORACLE) {
                anchor = CdcProbeResult.ORACLE_SOURCE;
            } else if (source.getType() == DatabaseType.POSTGRESQL) {
                anchor = CdcProbeResult.PG_SOURCE;
            } else {
                anchor = CdcProbeResult.VERSION;
            }
            return CdcProbeResult.of(List.of(CdcProbeResult.Check.of(anchor,
                    false, String.valueOf(e.getMessage()))));
        }
    }

    /**
     * One-click recovery for a CDC project (task book B7): stop the consumer, throw the
     * position away, and let the next start re-run the chunked full load from a fresh
     * offset. This is the ONLY way a project parked in ERROR by a fatal stream failure
     * (purged binlog, server-id collision, deserialization strike-out) comes back:
     * {@code CdcConsumerManager.ensureStarted} refuses to revive an ERROR-hatted project
     * on any node until a human rebuilds the baseline.
     *
     * <p>The sequence is exactly the one the Task 15 purge IT proved live — do not
     * reorder:
     * <ol>
     *   <li>{@code ensureStopped} — join the old stream first, so it can no longer
     *       write while the position is being deleted;</li>
     *   <li>{@code positionStore.delete} — the purged/stale position must not seed the
     *       next connect;</li>
     *   <li>{@code stateWriter.clearCursor} — polling-era cursors are meaningless to a
     *       re-baselining stream (same rule as the C2 switch);</li>
     *   <li>{@code taskStore.markCdcRunning} — lift the ERROR hat BEFORE the start:
     *       without this, {@code ensureStarted} silently skips the parked project;</li>
     *   <li>{@code ensureStarted(freshProject)} — re-read the row first so the consumer
     *       is built from the current config, not the snapshot taken before the purge
     *       (the start re-baselines: no position row means full load, then streaming).</li>
     * </ol>
     *
     * <p>Cross-node holder gate (final review FIX 1), BEFORE any mutation: on a node that
     * does not run the consumer, {@code ensureStopped} is a local no-op and
     * {@code ensureStarted} skips ("being consumed by another node") — yet
     * {@code positionStore.delete} would kill the holder's LIVE position row while the API
     * still answers {@code ok:true}. The holder's {@code advance()} then no-ops forever and
     * its next reconnect builds a client with {@code resumeFrom=null}, starting at the
     * server's current position: every event between deletion and reconnect is silently
     * skipped. Refuse while another LIVE owner holds the daemon lease. The residual race —
     * the holder dying right AFTER this check — is the recovery case and is accepted: its
     * lease expires, supervise takes over somewhere, and rebaseline then runs on a node
     * that passes this gate.
     *
     * <p>The trail (change log + audit) is written directly here, never from an
     * afterCommit callback: rebaseline runs controller-invoked without an ambient
     * transaction, so {@code changeLogRepository.save} and {@code auditService.record}
     * each open their own transaction and genuinely persist. (Task 16 ruling: the same
     * writes inside an afterCommit callback would join the already-committed
     * transaction and silently vanish.)
     *
     * @throws IllegalArgumentException with the bare key {@code error.project.cdc.not.cdc}
     *         when the project does not capture via BINLOG_CDC (the API controller turns
     *         that into a 400) — refused with ZERO consumer-manager interaction
     * @throws CdcNotHolderException with the bare key {@code cdc.rebaseline.not.holder}
     *         when this node runs no local consumer AND the daemon lease is held by
     *         another live owner (the API controller turns that into a 409) — refused
     *         with ZERO mutations and ZERO trail rows
     */
    public void rebaseline(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        if (!parseStrategy(project.getSyncConfig()).isLogCdc()) {
            throw new IllegalArgumentException("error.project.cdc.not.cdc");
        }
        // Holder gate (see javadoc). Read-only on purpose: acquire-then-release would race
        // the genuine holder (and briefly steal its lease). Liveness reuses the ONE TTL
        // notion supervise's takeover relies on — SyncLockService.isLeaseHeldByAnotherLiveOwner
        // mirrors SyncTaskRepository.acquireLock's "lockExpiresAt < now = takeable" clause.
        // statsFor != null means this node runs the consumer, so the lease cannot belong to
        // another LIVE owner — the local registration wins and the lookup is skipped.
        if (cdcManager.statsFor(projectId) == null
                && lockService.isLeaseHeldByAnotherLiveOwner(projectId)) {
            throw new CdcNotHolderException("cdc.rebaseline.not.holder");
        }
        log.info("Rebuilding the CDC baseline of project {} ('{}') on request",
                projectId, project.getName());
        cdcManager.ensureStopped(projectId);
        positionStore.delete(projectId);
        stateWriter.clearCursor(projectId);
        taskStore.markCdcRunning(projectId);                     // ERROR 帽子必须先摘
        Project fresh = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        cdcManager.ensureStarted(fresh);
        changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                fresh.getName(), ChangeType.INFO, "baseline rebuilt"));
        auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                "project " + projectId + ": baseline rebuilt");
    }

    /** Corrupt or absent sync_config degrades to POLLING, matching CdcConsumerManager. */
    private static CaptureStrategy parseStrategy(String json) {
        if (json == null || json.isBlank()) {
            return CaptureStrategy.POLLING;
        }
        try {
            return STRATEGY_JSON.readValue(json, SyncConfig.class).getCaptureStrategyOrDefault();
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable sync_config while resolving the capture strategy ({});"
                    + " treating it as POLLING", e.getMessage());
            return CaptureStrategy.POLLING;
        }
    }
}
