package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.CaptureStrategyParser;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
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

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final DataSourceManager dataSourceManager;
    private final SyncContextFactory contextFactory;
    private final BinlogCdcProbe probe;
    private final LogMinerCdcProbe logMinerProbe;
    private final PgLogicalReplicationCdcProbe pgProbe;
    private final SqlServerCdcProbe mssqlProbe;
    private final YStreamCdcProbe yStreamProbe;
    private final BinlogClientFactory clientFactory;
    private final CdcConsumerManager cdcManager;
    private final CdcPositionStore positionStore;
    private final SyncStateWriter stateWriter;
    private final SyncTaskStore taskStore;
    private final AdminAuditService auditService;
    private final ChangeLogRepository changeLogRepository;
    private final SyncLockService lockService;

    /** Optional so hand-built service tests keep their constructor; production injects it. */
    @Autowired(required = false)
    private SyncProperties properties;

    public CdcOperationsService(ProjectRepository projectRepository,
                                DatabaseConfigRepository databaseConfigRepository,
                                DataSourceManager dataSourceManager,
                                SyncContextFactory contextFactory,
                                BinlogCdcProbe probe,
                                LogMinerCdcProbe logMinerProbe,
                                PgLogicalReplicationCdcProbe pgProbe,
                                SqlServerCdcProbe mssqlProbe,
                                YStreamCdcProbe yStreamProbe,
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
        this.contextFactory = contextFactory;
        this.probe = probe;
        this.logMinerProbe = logMinerProbe;
        this.pgProbe = pgProbe;
        this.mssqlProbe = mssqlProbe;
        this.yStreamProbe = yStreamProbe;
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
     * <p>Dispatches by source family: the MySQL family (MySQL and every MySQL-wire
     * product) → binlog probe, which additionally decodes one real event on anything but
     * native MySQL; the PostgreSQL family (PostgreSQL and the PG-compatible domestic
     * kernels) → PG logical probe, which is given the project's own table selection so its
     * table-level checks actually run; literal Oracle → LogMiner probe; literal SQL
     * Server → its own probe; anything else → 400 {@code cdc.probe.unsupported}.
     *
     * @throws IllegalArgumentException with the bare key {@code cdc.probe.unsupported} when
     *         the project's source is missing or has no CDC integration (the API
     *         controller turns that into a 400)
     */
    public CdcProbeResult probeProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        DatabaseConfig source = project.getSourceDbId() == null ? null
                : databaseConfigRepository.findById(project.getSourceDbId()).orElse(null);
        // resolvedFamily(), not the preset's family: a CUSTOM connection that declares the
        // MySQL, PostgreSQL or Oracle family gets that family's checks (the same family its
        // dialect, type mapping and metadata reader already get). v1.9.0: LogMiner moved
        // onto this path too — DM/崖山 declare the Oracle family and the probe itself
        // decides what to report. SQL Server CDC stays keyed on the preset.
        if (source == null || source.getType() == null
                || (source.resolvedFamily() != DatabaseType.DialectFamily.MYSQL
                        && source.resolvedFamily() != DatabaseType.DialectFamily.ORACLE
                        && source.resolvedFamily() != DatabaseType.DialectFamily.POSTGRES
                        && source.getType() != DatabaseType.SQLSERVER)) {
            throw new IllegalArgumentException("cdc.probe.unsupported");
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            if (source.resolvedFamily() == DatabaseType.DialectFamily.MYSQL) {
                long plannedServerId = clientFactory.serverIdFor(projectId);
                // The saved source travels too: on anything but native MySQL the probe
                // opens a throwaway stream through this very config, so the credentials
                // and port it presents are the ones the consumer would use.
                return probe.probe(conn, source, plannedServerId);
            }
            if (source.resolvedFamily() == DatabaseType.DialectFamily.POSTGRES) {
                // v1.9.0: this IS the per-project probe, so it must carry the project's
                // tables — it used to pass an empty list (a comment copied from the
                // connection-test path), which silently disabled the table-level
                // REPLICA IDENTITY check: a table left at NOTHING passed the probe, then
                // delivered UPDATE/DELETE events without any old key, and the consumer
                // could neither find the original row nor delete it.
                return pgProbe.probe(conn, ownerTables(
                        contextFactory.parseConfig(project), source, conn));
            }
            if (source.getType() == DatabaseType.YASHANDB) {
                // 崖山 declares the ORACLE family but has no DBMS_LOGMNR (measured); its own
                // logical log engine is YStream. Dispatch by product, exactly like the save
                // gate and the page's strategy list. The project id lets the capacity check
                // exclude this project's own service — it is reused, not a new slot.
                return yStreamProbe.probe(conn, ownerTables(
                        contextFactory.parseConfig(project), source, conn), project.getId());
            }
            if (source.getType() == DatabaseType.SQLSERVER) {
                // Same rule as the PG branch: this IS the per-project probe, so it must
                // carry the selection. Without it the per-table capture-instance and
                // @role_name checks never ran, and a green report hid a table whose
                // changes could not actually be read.
                return mssqlProbe.probe(conn, ownerTables(
                        contextFactory.parseConfig(project), source, conn));
            }
            // Oracle's per-table checks (supplemental logging, LONG columns) need the
            // selection for the same reason.
            // P0-2: the saved source config travels so the CDB mining-mode check can
            // see whether a mining account is configured.
            return logMinerProbe.probe(conn, ownerTables(
                    contextFactory.parseConfig(project), source, conn), source);
        } catch (SQLException | RuntimeException e) {
            // Never an exception to the caller: degrade to a failed check carrying the
            // driver's message, mirroring BinlogCdcProbe.checkVersion's own SQLException path.
            log.debug("CDC probe could not reach the source of project {}: {}",
                    projectId, e.toString());
            String anchor;
            if (source.getType() == DatabaseType.YASHANDB) {
                anchor = CdcProbeResult.YSTREAM_SOURCE;
            } else if (source.resolvedFamily() == DatabaseType.DialectFamily.ORACLE) {
                anchor = CdcProbeResult.ORACLE_SOURCE;
            } else if (source.resolvedFamily()
                    == DatabaseType.DialectFamily.POSTGRES) {
                anchor = CdcProbeResult.PG_SOURCE;
            } else if (source.getType() == DatabaseType.SQLSERVER) {
                anchor = CdcProbeResult.MSSQL_SOURCE;
            } else {
                anchor = CdcProbeResult.VERSION;
            }
            return CdcProbeResult.of(List.of(CdcProbeResult.Check.of(anchor,
                    false, String.valueOf(e.getMessage()))));
        }
    }

    /**
     * The probe's {@code [schema, table]} pairs for a project's table selection.
     *
     * <p>Empty means "unknown, skip table-level checks", never "no tables": a legacy config
     * selects nothing explicitly and means "every object discovered in the source", a list
     * only a metadata read could produce — not this probe's job.
     *
     * <p>Table names are stored unqualified by the picker, so each one borrows the
     * connection's schema; an already-qualified {@code schema.table} entry keeps its own.
     * The schema comes from the saved connection, falling back to what the live connection
     * reports (a {@code search_path} default other than {@code public} would otherwise
     * make every table look missing).
     */
    private static List<String[]> ownerTables(SyncConfig config, DatabaseConfig source,
                                              Connection conn) {
        if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
            return List.of();
        }
        String schema = probeSchema(source, conn);
        List<String[]> pairs = new ArrayList<>(config.getTables().size());
        for (String name : config.getTables()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            int dot = trimmed.indexOf('.');
            if (dot > 0 && dot < trimmed.length() - 1) {
                pairs.add(new String[]{trimmed.substring(0, dot), trimmed.substring(dot + 1)});
            } else {
                pairs.add(new String[]{schema, trimmed});
            }
        }
        return pairs;
    }

    private static String probeSchema(DatabaseConfig source, Connection conn) {
        String configured = source.getSchemaName();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            return conn.getSchema();
        } catch (SQLException | RuntimeException e) {
            // No answer → the probe falls back to "public", its pre-v1.9.0 behavior.
            return null;
        }
    }

    /**
     * Adopts a target populated by an external consistent dump: records the dump's source
     * position as an already-completed baseline, so starting the project streams from there
     * instead of running another full load.
     *
     * <p>Supported first version: MySQL binlog (file/position/GTID), PostgreSQL logical
     * (pg_lsn), SQL Server CDC (canonical 0x LSN), Oracle/Dameng LogMiner (SCN) and Yashan
     * YStream (readable RESTART_POSITION, only when the project's existing YStream service
     * already reports that position). A database target, a single-target project and a
     * stopped project are required. The operator owns the dump's consistency; Jync only
     * proves the source still covers the supplied position where it can and never copies
     * rows.
     */
    public void adoptExternalBaseline(Long projectId, String binlogFile, Long binlogPos,
                                      String gtidSet, String lsn, Long scn) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        CaptureStrategy strategy = CaptureStrategyParser.parse(project.getSyncConfig());
        if (!strategy.isLogCdc()) {
            throw new IllegalArgumentException("error.cdc.external.notCdc");
        }
        if (project.getTargetKindOrDefault() != com.qqmu.jync.model.SyncTargetKind.JDBC) {
            throw new IllegalArgumentException("error.cdc.external.jdbcOnly");
        }
        SyncConfig config = contextFactory.parseConfig(project);
        if (config != null && config.getFanoutTargetDbIds() != null
                && !config.getFanoutTargetDbIds().isEmpty()) {
            throw new IllegalArgumentException("error.cdc.external.fanout");
        }
        if (Boolean.TRUE.equals(project.getEnabled())
                || cdcManager.statsFor(projectId) != null
                || lockService.isLeaseHeldByLiveOwner(projectId)) {
            throw new IllegalArgumentException("error.cdc.external.stopFirst");
        }
        DatabaseConfig source = project.getSourceDbId() == null ? null
                : databaseConfigRepository.findById(project.getSourceDbId()).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("error.cdc.external.notCdc");
        }
        cdcManager.ensureStopped(projectId);
        // Validate everything before deleting the existing position: a typo must not
        // destroy the anchor of a project the operator can still resume.
        switch (strategy) {
            case BINLOG_CDC -> {
                String file = binlogFile == null ? "" : binlogFile.trim();
                if (file.isEmpty() || binlogPos == null) {
                    throw new IllegalArgumentException("error.cdc.external.positionRequired");
                }
                long fileSize = binlogFileSize(source, file);
                if (binlogPos < 4L || binlogPos > fileSize) {
                    throw new IllegalArgumentException("error.cdc.external.positionInvalid");
                }
                positionStore.delete(projectId);
                positionStore.recordBaselineStart(projectId, file, binlogPos,
                        blankToNull(gtidSet), clientFactory.serverIdFor(projectId));
            }
            case PG_LOGICAL_CDC -> {
                String value = normalizedLsn(strategy, lsn);
                assertPgSlotCovers(projectId, source, value);
                positionStore.delete(projectId);
                positionStore.recordBaselineStartLsn(projectId, value);
            }
            case SQLSERVER_CDC -> {
                String value = normalizedLsn(strategy, lsn);
                assertSqlServerCapture(source, config);
                positionStore.delete(projectId);
                positionStore.recordBaselineStartLsn(projectId, value);
            }
            case LOGMINER_CDC -> {
                if (scn == null || scn <= 0L) {
                    throw new IllegalArgumentException("error.cdc.external.positionRequired");
                }
                assertScnStillMineable(source, scn);
                positionStore.delete(projectId);
                positionStore.recordBaselineStartScn(projectId, scn);
            }
            case YSTREAM_CDC -> {
                String packed = yStreamPosition(projectId, source, lsn);
                positionStore.delete(projectId);
                positionStore.recordBaselineStartLsn(projectId, packed);
            }
            default -> throw new IllegalArgumentException("error.cdc.external.notCdc");
        }
        positionStore.markBaselineDone(projectId);
        taskStore.markStoppedClearingResult(projectId); // lift an old ERROR hat
        String adopted = switch (strategy) {
            case BINLOG_CDC -> binlogFile.trim() + ":" + binlogPos;
            case LOGMINER_CDC -> "SCN " + scn;
            default -> strategy.name() + " " + (lsn == null ? "" : lsn.trim());
        };
        changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                project.getName(), ChangeType.INFO,
                "external baseline adopted at " + adopted));
        auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                AuditDetail.of("audit.detail.cdcRebaseline", String.valueOf(projectId),
                        "@adoptExternalBaseline:" + adopted));
        log.info("Project {} adopted an external baseline at {} (strategy {})",
                projectId, adopted, strategy);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizedLsn(CaptureStrategy strategy, String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            throw new IllegalArgumentException("error.cdc.external.positionRequired");
        }
        if (strategy == CaptureStrategy.SQLSERVER_CDC) {
            String canonical = SqlServerCdcConsumer.canonicalLsn(value);
            if (canonical == null || !canonical.startsWith("0x") || canonical.length() != 22) {
                throw new IllegalArgumentException("error.cdc.external.positionInvalid");
            }
            return canonical;
        }
        if (!value.matches("[0-9A-Fa-f]+/[0-9A-Fa-f]+")) {
            throw new IllegalArgumentException("error.cdc.external.positionInvalid");
        }
        return value;
    }

    /**
     * SQL Server: with a recorded LSN and no capture instance, the consumer has nothing to
     * poll and would look healthy forever. Require the selected tables to have a capture
     * instance before adopting a position.
     */
    private void assertSqlServerCapture(DatabaseConfig source, SyncConfig config) {
        java.util.Set<String> selected = new java.util.HashSet<>();
        if (config != null && config.getTables() != null) {
            for (String table : config.getTables()) {
                if (table != null && !table.isBlank()) {
                    selected.add(table.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        try (Connection conn = dataSourceManager.getConnection(source);
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT OBJECT_NAME(source_object_id) FROM cdc.change_tables")) {
            java.util.Set<String> captured = new java.util.HashSet<>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null) {
                    captured.add(name.toUpperCase(java.util.Locale.ROOT));
                }
            }
            if (captured.isEmpty()
                    || (!selected.isEmpty() && !captured.containsAll(selected))) {
                throw new IllegalArgumentException("error.cdc.external.sqlserverCapture");
            }
        } catch (SQLException e) {
            log.warn("Could not read SQL Server CDC capture tables for the external baseline:"
                    + " {}", e.getMessage());
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
        }
    }

    /**
     * PostgreSQL: the consumer peeks from the slot's confirmed position, never from the
     * stored LSN. A missing slot parks later; a slot already ahead of the supplied position
     * would silently skip the changes between the dump and the slot. Reject both up front.
     */
    private void assertPgSlotCovers(Long projectId, DatabaseConfig source, String lsn) {
        if (properties == null) {
            return;   // hand-built unit tests; production always has the bean
        }
        String slot;
        try {
            slot = PgLogicalCdcConsumer.slotName(projectId, properties);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
        }
        try (Connection conn = dataSourceManager.getConnection(source);
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT confirmed_flush_lsn FROM pg_replication_slots WHERE slot_name = ?")) {
            ps.setString(1, slot);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("error.cdc.external.pgSlotMissing");
                }
                String confirmed = rs.getString(1);
                if (confirmed != null && CdcPositionStore.compareLsn(confirmed, lsn) > 0) {
                    throw new IllegalArgumentException("error.cdc.external.pgSlotAhead");
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read PG replication slot '{}' for the external baseline: {}",
                    slot, e.getMessage());
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
        }
    }

    /** LogMiner: reject a SCN the source's retained redo can no longer mine. */
    private void assertScnStillMineable(DatabaseConfig source, long scn) {
        try (Connection conn = dataSourceManager.getConnection(source)) {
            Long floor = MiningDialects.detect(conn).oldestMineableScn(conn);
            if (floor != null && floor > scn) {
                throw new IllegalArgumentException("error.cdc.external.scnPurged");
            }
        } catch (SQLException e) {
            log.warn("Could not read the oldest mineable SCN for the external baseline: {}",
                    e.getMessage());
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
        }
    }

    /**
     * YStream: the client position is packed by {@link YStreamPositionCodec}, and the
     * lifecycle only adopts a stored position when exactly one service exists. Accept the
     * readable RESTART_POSITION only when that service already reports it; otherwise a new
     * service would start at "now" and silently skip the gap.
     */
    private String yStreamPosition(Long projectId, DatabaseConfig source, String raw) {
        String text = blankToNull(raw);
        if (text == null) {
            throw new IllegalArgumentException("error.cdc.external.positionRequired");
        }
        YStreamPositionCodec.YStreamPosition wanted;
        try {
            wanted = YStreamPositionCodec.parseText(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.cdc.external.positionInvalid");
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            java.util.List<YStreamServiceAdmin.Service> services =
                    new JdbcYStreamServiceOps(conn).services(projectId);
            if (services.size() != 1) {
                throw new IllegalArgumentException("error.cdc.external.ystreamService");
            }
            YStreamPositionCodec.YStreamPosition existing =
                    YStreamPositionCodec.parseText(services.get(0).restartPosition());
            if (!existing.equals(wanted)) {
                throw new IllegalArgumentException("error.cdc.external.ystreamPosition");
            }
            return YStreamPositionCodec.encode(wanted);
        } catch (SQLException e) {
            log.warn("Could not read YStream services for the external baseline: {}",
                    e.getMessage());
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
        }
    }

    /** Reads the size of one binlog file; a missing file or unreadable list fails closed. */
    private long binlogFileSize(DatabaseConfig source, String file) {
        try (Connection conn = dataSourceManager.getConnection(source);
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SHOW BINARY LOGS")) {
            while (rs.next()) {
                if (file.equals(rs.getString(1))) {
                    return rs.getLong(2);
                }
            }
            throw new IllegalArgumentException("error.cdc.external.fileMissing");
        } catch (SQLException e) {
            log.warn("Could not read SHOW BINARY LOGS for the external baseline: {}",
                    e.getMessage());
            throw new IllegalArgumentException("error.cdc.external.probeFailed", e);
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
     * <p>DISABLED project (server-side enforcement, so a direct API call cannot bypass
     * it): there is deliberately no start. Supervision only keeps consumers of enabled
     * projects, so a consumer started here would begin the full load, be asked to stop on
     * the next tick, and be interrupted at a chunk boundary after the join budget — the
     * page would show "stopped" next to "CDC running" and a held lock meanwhile, and the
     * load could never finish. The reset itself (position + cursors + ERROR hat + old
     * result text) is the useful part: the next real start finds no position and runs the
     * full baseline. {@code markStoppedClearingResult} — not {@code markCdcRunning} —
     * clears the ERROR hat without claiming a consumer that is not running.
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
     * @throws CdcRestartFailedException with the bare key
     *         {@code error.cdc.rebaseline.restartFailed} when the reset committed but the
     *         consumer could not be started (the API controller turns that into a 503);
     *         the reset stands and supervision retries the start
     * @return {@code true} when the consumer was restarted (enabled project, full load
     *         started now); {@code false} when the task is disabled and only the baseline
     *         state was reset — the full load runs on the next start
     */
    public boolean rebaseline(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        if (!CaptureStrategyParser.parse(project.getSyncConfig()).isLogCdc()) {
            throw new IllegalArgumentException("error.project.cdc.not.cdc");
        }
        // Holder gate (see javadoc). Read-only on purpose: acquire-then-release would race
        // the genuine holder (and briefly steal its lease). Liveness reuses the ONE TTL
        // notion supervise's takeover relies on — SyncLockService.isLeaseHeldByAnotherLiveOwner
        // mirrors SyncTaskRepository.acquireLock's "lockExpiresAt < now = takeable" clause.
        // statsFor != null means this node runs the consumer, so the lease cannot belong to
        // another LIVE owner — the local registration wins and the lookup is skipped.
        // Always consult the lease: a local registration can exist for a thread that has
        // already lost the lease (force-unlock / takeover) until supervise prunes it, and
        // deleting the position then would strand the real owner's stream.
        if (lockService.isLeaseHeldByAnotherLiveOwner(projectId)) {
            throw new CdcNotHolderException("cdc.rebaseline.not.holder");
        }
        log.info("Rebuilding the CDC baseline of project {} ('{}') on request",
                projectId, project.getName());
        cdcManager.ensureStopped(projectId);
        positionStore.delete(projectId);
        stateWriter.clearCursor(projectId);
        // Re-read AFTER the stop: the row may have been disabled while the join waited.
        Project fresh = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        boolean restarted = Boolean.TRUE.equals(fresh.getEnabled());
        if (restarted) {
            taskStore.markCdcRunning(projectId);                 // ERROR 帽子必须先摘
            try {
                cdcManager.ensureStarted(fresh);
            } catch (RuntimeException e) {
                // The destructive half already committed: the position row is gone, the
                // cursors are cleared and the ERROR hat is lifted. Letting this escape as a
                // generic 500 hides that the reset did succeed and that retrying (or letting
                // supervision start the consumer) is the next step. Trail the partial
                // outcome, then report a fixed, translatable key.
                log.error("CDC baseline of project {} was reset, but the consumer could not be"
                        + " started; supervision will retry", projectId, e);
                changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                        fresh.getName(), ChangeType.ERROR,
                        "baseline reset; consumer restart failed"));
                auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                        AuditDetail.of("audit.detail.cdcRebaseline", String.valueOf(projectId),
                                "@rebaseline:restartFailed"));
                throw new CdcRestartFailedException(e);
            }
        } else {
            // Disabled: reset only. markStoppedClearingResult lifts the ERROR hat
            // (hasErrorHat reads status) AND drops the old failure text, so the page does
            // not keep showing a stale error next to a task the user just reset. Enabling
            // the task later starts the consumer — with no position row, that start runs
            // the full baseline.
            taskStore.markStoppedClearingResult(projectId);
            log.info("CDC baseline of disabled project {} reset; it will be rebuilt when"
                    + " the task is started", projectId);
        }
        String trail = restarted ? "baseline rebuilt" : "baseline reset (task disabled)";
        changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                fresh.getName(), ChangeType.INFO, trail));
        auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                AuditDetail.of("audit.detail.cdcRebaseline", String.valueOf(projectId),
                        "@rebaseline:" + (restarted ? "rebuilt" : "reset")));
        return restarted;
    }

    /**
     * Continues a baseline that stopped halfway. Unlike {@link #rebaseline(Long)}, this keeps
     * the original capture anchor and the per-table chunk checkpoints, so completed tables
     * are not re-read and the in-flight tables resume from their last committed block.
     */
    public boolean resumeBaseline(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        if (!CaptureStrategyParser.parse(project.getSyncConfig()).isLogCdc()) {
            throw new IllegalArgumentException("error.project.cdc.not.cdc");
        }
        // Always consult the lease: a local registration can exist for a thread that has
        // already lost the lease (force-unlock / takeover) until supervise prunes it, and
        // deleting the position then would strand the real owner's stream.
        if (lockService.isLeaseHeldByAnotherLiveOwner(projectId)) {
            throw new CdcNotHolderException("cdc.rebaseline.not.holder");
        }
        var position = positionStore.find(projectId).orElse(null);
        if (position == null || Boolean.TRUE.equals(position.isBaselineDone())) {
            throw new IllegalArgumentException("error.project.cdc.resume.noPending");
        }
        log.info("Resuming the unfinished CDC baseline of project {} ('{}')",
                projectId, project.getName());
        cdcManager.ensureStopped(projectId);
        Project fresh = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        boolean restarted = Boolean.TRUE.equals(fresh.getEnabled());
        if (restarted) {
            taskStore.markCdcRunning(projectId);                 // lift the ERROR hat
            try {
                cdcManager.ensureStarted(fresh);
            } catch (RuntimeException e) {
                log.error("CDC baseline of project {} could not be resumed; supervision will"
                        + " retry", projectId, e);
                changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                        fresh.getName(), ChangeType.ERROR,
                        "baseline resume; consumer restart failed"));
                auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                        AuditDetail.of("audit.detail.cdcRebaseline", String.valueOf(projectId),
                                "@rebaseline:resumeFailed"));
                throw new CdcRestartFailedException(e);
            }
        } else {
            taskStore.markStoppedClearingResult(projectId);
        }
        changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                fresh.getName(), ChangeType.INFO,
                restarted ? "baseline resume requested" : "baseline resume pending start"));
        auditService.record(AuditAction.PROJECT_CDC_REBASELINE,
                AuditDetail.of("audit.detail.cdcRebaseline", String.valueOf(projectId),
                        "@rebaseline:" + (restarted ? "resumed" : "resumePending")));
        return restarted;
    }

}
