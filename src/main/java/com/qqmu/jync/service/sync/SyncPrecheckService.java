package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcSchemaDrift;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.CursorIndexProbe;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.TargetTableVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * Rehearses a sync cycle without touching either side.
 *
 * <p>The point is to answer "what will happen on the next run?" before it happens: pending
 * DDL, target tables with the wrong shape, polling options that will silently do nothing.
 * It reads the same metadata and runs the same detector as the engine, but it never applies
 * DDL, never writes snapshots, and never records progress.
 *
 * <p>Checks are reported, not thrown. A project whose source is down produces a report with a
 * connectivity ERROR, because "the precheck found a problem" is a successful precheck.
 */
@Service
@Slf4j
public class SyncPrecheckService {

    private static final String CONNECTION = "CONNECTION";
    private static final String PROJECT = "PROJECT";

    private final ProjectRepository projectRepository;
    private final SyncContextFactory contextFactory;
    private final DataSourceManager dataSourceManager;
    private final SyncEngine syncEngine;
    private final ChangeDetector changeDetector;
    private final CursorStrategyResolver cursorResolver;
    private final CdcOperationsService cdcOperations;
    private final CdcPositionStore cdcPositions;
    private final SyncProperties properties;

    public SyncPrecheckService(ProjectRepository projectRepository,
                               SyncContextFactory contextFactory,
                               DataSourceManager dataSourceManager,
                               SyncEngine syncEngine,
                               ChangeDetector changeDetector,
                               CursorStrategyResolver cursorResolver,
                               CdcOperationsService cdcOperations,
                               CdcPositionStore cdcPositions,
                               SyncProperties properties) {
        this.projectRepository = projectRepository;
        this.contextFactory = contextFactory;
        this.dataSourceManager = dataSourceManager;
        this.syncEngine = syncEngine;
        this.changeDetector = changeDetector;
        this.cursorResolver = cursorResolver;
        this.cdcOperations = cdcOperations;
        this.cdcPositions = cdcPositions;
        this.properties = properties;
    }

    public PrecheckReport precheck(Long projectId) {
        long started = System.nanoTime();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        List<PrecheckReport.Check> checks = new ArrayList<>();

        SyncContext ctx;
        try {
            ctx = contextFactory.build(project, "precheck", null);
        } catch (RuntimeException e) {
            checks.add(error("connection.config", PROJECT, project.getName(),
                    "precheck.connection.config", messageOf(e)));
            return report(project, checks, started);
        }

        Connection sourceConn = null;
        Connection targetConn = null;
        try {
            try {
                sourceConn = dataSourceManager.getConnection(ctx.getSourceConfig());
                checks.add(ok("connection.source", CONNECTION, ctx.getSourceConfig().getName(),
                        "precheck.connection.ok", null));
            } catch (Exception e) {
                checks.add(error("connection.source", CONNECTION,
                        ctx.getSourceConfig().getName(), "precheck.connection.failed",
                        messageOf(e)));
            }
            try {
                targetConn = dataSourceManager.getConnection(ctx.getTargetConfig());
                checks.add(ok("connection.target", CONNECTION, ctx.getTargetConfig().getName(),
                        "precheck.connection.ok", null));
            } catch (Exception e) {
                checks.add(error("connection.target", CONNECTION,
                        ctx.getTargetConfig().getName(), "precheck.connection.failed",
                        messageOf(e)));
            }
            if (sourceConn == null || targetConn == null) {
                return report(project, checks, started);
            }

            DatabaseMeta sourceMeta;
            try {
                sourceMeta = syncEngine.readSourceMetadata(sourceConn, ctx);
            } catch (SQLException | RuntimeException e) {
                checks.add(error("source.metadata", CONNECTION,
                        ctx.getSourceConfig().getName(), "precheck.source.metadataFailed",
                        messageOf(e)));
                return report(project, checks, started);
            }

            checkStructure(ctx, sourceConn, sourceMeta, checks);
            checkTarget(ctx, targetConn, sourceMeta, checks);
            checkPolling(ctx, sourceConn, sourceMeta, checks);
            checkCdc(ctx, sourceMeta, checks);
        } finally {
            closeQuietly(sourceConn);
            closeQuietly(targetConn);
        }
        return report(project, checks, started);
    }

    /**
     * Pending structural work and name-mapping collisions.
     *
     * <p>{@link ChangeDetector#detect} is read-only against the snapshot store, so this is
     * the same list the next cycle would apply, in the same order.
     */
    private void checkStructure(SyncContext ctx, Connection sourceConn, DatabaseMeta sourceMeta,
                                List<PrecheckReport.Check> checks) {
        SyncConfig config = ctx.getConfig();
        List<String> conflicts = config.tableMappingConflicts(
                sourceMeta.getTables().stream().map(TableMeta::getName).toList());
        for (String conflict : conflicts) {
            checks.add(error("structure.mapping-conflict", PROJECT, ctx.projectName(),
                    "precheck.structure.mappingConflict", conflict));
        }

        List<ChangeEvent> events;
        try {
            events = changeDetector.detect(ctx.projectId(), sourceMeta, config,
                    () -> ctx.getSourceReader().schemaListingIsComplete(sourceConn,
                            ctx.getSourceSchema()));
        } catch (RuntimeException e) {
            checks.add(error("structure.detect", PROJECT, ctx.projectName(),
                    "precheck.structure.detectFailed", messageOf(e)));
            return;
        }
        if (events.isEmpty()) {
            if (conflicts.isEmpty()) {
                checks.add(ok("structure.pending", PROJECT, ctx.projectName(),
                        "precheck.structure.none", null));
            }
            return;
        }
        for (ChangeEvent event : events) {
            checks.add(new PrecheckReport.Check("structure.pending",
                    PrecheckReport.Severity.WARN,
                    event.getObjectType() == null ? PROJECT : event.getObjectType().name(),
                    event.getObjectName(),
                    structureMessageKey(event),
                    event.getDescription()));
        }
    }

    /**
     * Whether every selected source table has a usable target counterpart.
     *
     * <p>Missing tables and columns are WARN with structure sync on (the next cycle creates
     * or adds them) and ERROR with it off (nothing will). A shape mismatch that structure
     * sync cannot repair — a wrong type, an Oracle identity column that rejects explicit
     * values — is always an ERROR.
     */
    private void checkTarget(SyncContext ctx, Connection targetConn, DatabaseMeta sourceMeta,
                             List<PrecheckReport.Check> checks) {
        SyncConfig config = ctx.getConfig();
        DatabaseType targetType = ctx.effectiveTargetType();
        for (TableMeta table : sourceMeta.getTables()) {
            if (!config.includesTable(table.getName())) {
                continue;
            }
            String targetTable = config.targetTableName(table.getName());
            try {
                if (!TargetTableVerifier.tableExists(targetConn, ctx.getTargetSchema(),
                        targetTable, targetType)) {
                    checks.add(new PrecheckReport.Check("target.table.missing",
                            config.isSyncStructure()
                                    ? PrecheckReport.Severity.WARN
                                    : PrecheckReport.Severity.ERROR,
                            "TABLE", table.getName(),
                            config.isSyncStructure()
                                    ? "precheck.target.tableWillBeCreated"
                                    : "precheck.target.tableMissing",
                            targetTable));
                    continue;
                }
                checks.add(ok("target.table", "TABLE", table.getName(),
                        "precheck.target.tableExists", targetTable));

                List<ColumnMeta> missing = TargetTableVerifier.missingColumns(targetConn,
                        ctx.getTargetSchema(), targetTable, table, targetType);
                if (!missing.isEmpty()) {
                    checks.add(new PrecheckReport.Check("target.table.columns",
                            config.isSyncStructure()
                                    ? PrecheckReport.Severity.WARN
                                    : PrecheckReport.Severity.ERROR,
                            "TABLE", table.getName(),
                            config.isSyncStructure()
                                    ? "precheck.target.columnsWillBeAdded"
                                    : "precheck.target.columnsMissing",
                            names(missing)));
                    continue;
                }

                String incompatible = TargetTableVerifier.verify(targetConn,
                        ctx.getTargetSchema(), targetTable, table, targetType);
                if (incompatible != null) {
                    checks.add(error("target.table.incompatible", "TABLE", table.getName(),
                            "precheck.target.incompatible", incompatible));
                    continue;
                }

                List<String> comparable = table.getColumns().stream()
                        .map(ColumnMeta::getName)
                        .filter(name -> !isPrimaryKey(table, name))
                        .toList();
                List<String> uncomparable = TargetTableVerifier.uncomparableColumns(targetConn,
                        ctx.getTargetSchema(), targetTable, targetType, comparable);
                if (!uncomparable.isEmpty()) {
                    checks.add(new PrecheckReport.Check("target.columns.uncomparable",
                            PrecheckReport.Severity.WARN, "TABLE", table.getName(),
                            "precheck.target.uncomparableColumns", String.join(", ",
                                    uncomparable)));
                }
            } catch (SQLException | RuntimeException e) {
                checks.add(error("target.inspect", "TABLE", table.getName(),
                        "precheck.target.inspectFailed", messageOf(e)));
            }
        }
    }

    /**
     * What the polling path will do per table: which cursor it picks, whether that cursor is
     * indexed, and whether the table has a primary key. CDC projects are judged separately
     * (their layout checks live with the CDC probe), so this section stays quiet for them.
     */
    private void checkPolling(SyncContext ctx, Connection sourceConn, DatabaseMeta sourceMeta,
                              List<PrecheckReport.Check> checks) {
        SyncConfig config = ctx.getConfig();
        if (!config.isSyncData() || config.getCaptureStrategyOrDefault().isLogCdc()) {
            return;
        }
        for (TableMeta table : sourceMeta.getTables()) {
            if (!config.includesTable(table.getName())) {
                continue;
            }
            CursorStrategy strategy;
            try {
                strategy = cursorResolver.resolve(table, config);
            } catch (RuntimeException e) {
                checks.add(error("polling.cursor", "TABLE", table.getName(),
                        "precheck.polling.cursorFailed", messageOf(e)));
                continue;
            }
            if (strategy.getKind() == CursorStrategy.Kind.NONE) {
                checks.add(new PrecheckReport.Check("polling.no-cursor",
                        PrecheckReport.Severity.WARN, "TABLE", table.getName(),
                        "precheck.polling.noCursor", strategy.getRationale()));
            } else if (strategy.getColumn() != null && !strategy.isPseudoColumn()) {
                Boolean indexed = CursorIndexProbe.columnLeadsIndex(sourceConn,
                        ctx.resolveSourceFamily(), ctx.getSourceSchema(), table.getName(),
                        strategy.getColumn());
                if (Boolean.FALSE.equals(indexed)) {
                    checks.add(new PrecheckReport.Check("polling.unindexed-cursor",
                            PrecheckReport.Severity.WARN, "TABLE", table.getName(),
                            "precheck.polling.unindexedCursor", strategy.getColumn()));
                }
            }
            if (!table.hasPrimaryKey()) {
                checks.add(new PrecheckReport.Check("polling.no-primary-key",
                        PrecheckReport.Severity.WARN, "TABLE", table.getName(),
                        "precheck.polling.noPrimaryKey", null));
            }
        }
    }

    /**
     * CDC readiness, using the same probe the detail page runs — read-only: it opens a
     * connection and issues capability queries, never starts a consumer or touches a
     * position.
     *
     * <p>For binlog projects the persisted per-table layouts are compared with the current
     * catalog. A mismatch is exactly what {@code CdcSchemaDrift} refuses at restart, so the
     * precheck reports it as an ERROR before the user runs the project.
     */
    private void checkCdc(SyncContext ctx, DatabaseMeta sourceMeta,
                          List<PrecheckReport.Check> checks) {
        CaptureStrategy strategy = ctx.getConfig().getCaptureStrategyOrDefault();
        if (!strategy.isLogCdc()) {
            return;
        }

        CdcProbeResult probe;
        try {
            probe = cdcOperations.probeProject(ctx.projectId());
        } catch (IllegalArgumentException e) {
            if ("cdc.probe.unsupported".equals(e.getMessage())) {
                checks.add(new PrecheckReport.Check("cdc.probe.unsupported",
                        PrecheckReport.Severity.SKIP, "CDC", ctx.projectName(),
                        "precheck.cdc.unsupported", null));
            } else {
                checks.add(error("cdc.probe.failed", "CDC", ctx.projectName(),
                        "precheck.cdc.probeFailed", messageOf(e)));
            }
            return;
        } catch (RuntimeException e) {
            checks.add(error("cdc.probe.failed", "CDC", ctx.projectName(),
                    "precheck.cdc.probeFailed", messageOf(e)));
            return;
        }
        if (probe == null) {
            checks.add(error("cdc.probe.failed", "CDC", ctx.projectName(),
                    "precheck.cdc.probeFailed", null));
            return;
        }

        List<CdcProbeResult.Check> failures = probe.failures();
        for (CdcProbeResult.Check failure : failures) {
            checks.add(new PrecheckReport.Check("cdc.probe." + failure.getName(),
                    PrecheckReport.Severity.ERROR, "CDC", failure.getName(),
                    "cdc.check." + failure.getName(), failure.getDetail()));
        }
        if (failures.isEmpty()) {
            checks.add(ok("cdc.probe", "CDC", ctx.projectName(),
                    "precheck.cdc.probeOk", null));
        }

        if (strategy == CaptureStrategy.BINLOG_CDC) {
            checkBinlogLayout(ctx, sourceMeta, checks);
        }
    }

    private void checkBinlogLayout(SyncContext ctx, DatabaseMeta sourceMeta,
                                   List<PrecheckReport.Check> checks) {
        Optional<CdcPosition> position = cdcPositions.find(ctx.projectId());
        if (!position.isPresent()
                || !Boolean.TRUE.equals(position.get().isBaselineDone())) {
            // The next start re-reads every table before streaming, so stored layouts (if
            // any) describe an older snapshot and must not be compared.
            checks.add(new PrecheckReport.Check("cdc.baseline.pending",
                    PrecheckReport.Severity.WARN, "CDC", ctx.projectName(),
                    "precheck.cdc.baselinePending", null));
            return;
        }

        Map<String, String> persisted =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        persisted.putAll(cdcPositions.layoutSignatures(ctx.projectId()));
        if (persisted.isEmpty()) {
            checks.add(new PrecheckReport.Check("cdc.layout.missing",
                    PrecheckReport.Severity.WARN, "CDC", ctx.projectName(),
                    "precheck.cdc.layoutMissing", null));
            return;
        }

        for (TableMeta table : sourceMeta.getTables()) {
            if (!ctx.getConfig().includesTable(table.getName())) {
                continue;
            }
            String current = CdcTableBinding.layoutSignature(table);
            if (current == null) {
                continue;
            }
            String key = ctx.getSourceSchema() + "." + table.getName();
            String recorded = persisted.get(key);
            if (recorded == null) {
                checks.add(new PrecheckReport.Check("cdc.layout.missing",
                        PrecheckReport.Severity.WARN, "TABLE", table.getName(),
                        "precheck.cdc.layoutMissing", key));
            } else if (!recorded.equalsIgnoreCase(current)) {
                checks.add(new PrecheckReport.Check("cdc.layout.drift",
                        PrecheckReport.Severity.ERROR, "TABLE", table.getName(),
                        "precheck.cdc.layoutDrift", CdcSchemaDrift.MARKER + ": table "
                                + key + "; re-baseline the project to rebuild it from the"
                                + " current source schema"));
            }
        }
    }

    private static String structureMessageKey(ChangeEvent event) {
        if (event.getChangeType() == null) {
            return "precheck.structure.pending";
        }
        switch (event.getChangeType()) {
            case CREATE:
                return "precheck.structure.create";
            case ALTER:
                return "precheck.structure.alter";
            case DROP:
                return "precheck.structure.drop";
            default:
                return "precheck.structure.pending";
        }
    }

    private static boolean isPrimaryKey(TableMeta table, String column) {
        return table.getPrimaryKeys() != null && table.getPrimaryKeys().stream()
                .anyMatch(pk -> pk.equalsIgnoreCase(column));
    }

    private static String names(List<ColumnMeta> columns) {
        return columns.stream().map(ColumnMeta::getName).collect(Collectors.joining(", "));
    }

    private static PrecheckReport.Check ok(String code, String objectType, String objectName,
                                           String messageKey, String detail) {
        return new PrecheckReport.Check(code, PrecheckReport.Severity.OK, objectType,
                objectName, messageKey, detail);
    }

    private static PrecheckReport.Check error(String code, String objectType, String objectName,
                                              String messageKey, String detail) {
        return new PrecheckReport.Check(code, PrecheckReport.Severity.ERROR, objectType,
                objectName, messageKey, detail);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("Could not close precheck connection: {}", e.getMessage());
        }
    }

    private PrecheckReport report(Project project, List<PrecheckReport.Check> checks,
                                  long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new PrecheckReport(project.getId(), project.getName(), Instant.now(), elapsedMs,
                List.copyOf(checks));
    }
}
