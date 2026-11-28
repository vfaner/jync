package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.CdcPositionRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.util.TargetTableVerifier;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies the shape of an externally seeded target after a baseline is adopted.
 *
 * <p>Row counts are deliberately not compared: the dump is a snapshot at the supplied
 * position, while the source keeps moving. A difference is normal and would turn every
 * verification into a false failure. What can be checked safely is the contract that makes
 * replay possible: every covered source table exists on the target and every source column
 * is present with a compatible type.
 *
 * <p>The check runs in the background because a project can cover hundreds of tables. Its
 * result is stored on {@link CdcPosition} so the CDC card can show it after a later page load
 * or another node reads the same metadata row.
 */
@Slf4j
@Service
public class ExternalBaselineVerificationService {

    private static final int MAX_DETAIL_LINES = 100;

    private final ProjectRepository projectRepository;
    private final SyncContextFactory contextFactory;
    private final DataSourceManager dataSourceManager;
    private final CdcPositionStore positionStore;
    private final CdcPositionRepository positionRepository;
    private final ChangeLogRepository changeLogRepository;
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "jync-baseline-verify");
        thread.setDaemon(true);
        return thread;
    });

    public ExternalBaselineVerificationService(ProjectRepository projectRepository,
                                               SyncContextFactory contextFactory,
                                               DataSourceManager dataSourceManager,
                                               CdcPositionStore positionStore,
                                               CdcPositionRepository positionRepository,
                                               ChangeLogRepository changeLogRepository) {
        this.projectRepository = projectRepository;
        this.contextFactory = contextFactory;
        this.dataSourceManager = dataSourceManager;
        this.positionStore = positionStore;
        this.positionRepository = positionRepository;
        this.changeLogRepository = changeLogRepository;
    }

    /** Starts the check unless one is already queued/running for this project. */
    public void verifyAsync(Long projectId) {
        if (!active.add(projectId)) {
            return;
        }
        try {
            // Do not mark RUNNING for a row that has no external baseline: verifyNow would
            // return immediately, leaving the card frozen on "verifying" forever.
            CdcPosition position = positionStore.find(projectId).orElse(null);
            if (position == null || !position.hasExternalBaseline()) {
                active.remove(projectId);
                return;
            }
            positionStore.markExternalBaselineVerification(projectId,
                    CdcPosition.VERIFY_RUNNING, null, null, Instant.now());
        } catch (RuntimeException e) {
            // The adoption itself has already committed; a metadata-store hiccup here must
            // not turn it into a failed request. The status simply stays PENDING.
            active.remove(projectId);
            log.warn("Could not mark external baseline verification RUNNING for project {}: {}",
                    projectId, e.toString());
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    verifyNow(projectId);
                } catch (RuntimeException e) {
                    log.warn("External baseline verification for project {} failed: {}",
                            projectId, e.toString());
                    try {
                        finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                                "verification failed: " + messageOf(e), null);
                    } catch (RuntimeException nested) {
                        // The metadata store is the thing that failed; nothing more to do.
                        log.warn("Could not record the failed external baseline verification"
                                + " for project {}: {}", projectId, nested.toString());
                    }
                } finally {
                    active.remove(projectId);
                }
            });
        } catch (RejectedExecutionException e) {
            active.remove(projectId);
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "verification executor refused the job", null);
        }
    }

    /**
     * A node that died mid-check leaves RUNNING behind. Recover only rows old enough that no
     * live verification could still be progressing; a fresh 30-minute run is treated as
     * abandoned and shown as pending again instead of "stuck forever".
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleRuns() {
        try {
            for (CdcPosition position : positionRepository
                    .findByExternalBaselineVerifyStatusAndExternalBaselineVerifiedAtBefore(
                            CdcPosition.VERIFY_RUNNING,
                            Instant.now().minusSeconds(30 * 60))) {
                positionStore.markExternalBaselineVerification(position.getProjectId(),
                        CdcPosition.VERIFY_PENDING, null, null, null);
            }
        } catch (RuntimeException e) {
            log.warn("Could not recover stale external-baseline checks: {}", e.toString());
        }
    }

    /** Synchronous entry point, also used by tests and a manual retry. */
    public void verifyNow(Long projectId) {
        CdcPosition position = positionStore.find(projectId).orElse(null);
        if (position == null || !position.hasExternalBaseline()) {
            return;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "project no longer exists", null);
            return;
        }
        SyncConfig config;
        try {
            config = contextFactory.parseConfig(project);
            if (config == null) {
                config = new SyncConfig();
            }
        } catch (RuntimeException e) {
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "cannot read project configuration: " + messageOf(e), null);
            return;
        }
        Set<String> tables;
        try {
            tables = coveredTables(project, config, position);
        } catch (SQLException | RuntimeException e) {
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "cannot resolve the external baseline table list: " + messageOf(e), null);
            return;
        }
        if (tables.isEmpty()) {
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "the external baseline has no table scope", null);
            return;
        }

        SyncContext ctx;
        try {
            ctx = contextFactory.build(project, "baseline-verify", null);
        } catch (RuntimeException e) {
            finish(projectId, CdcPosition.VERIFY_ERROR, 1,
                    "cannot open the source/target metadata: " + messageOf(e), null);
            return;
        }

        List<String> issues = new ArrayList<>();
        int checked = 0;
        try (Connection source = dataSourceManager.getConnection(ctx.getSourceConfig());
             Connection target = dataSourceManager.getConnection(ctx.getTargetConfig())) {
            for (String table : tables) {
                checked++;
                String issue = verifyTable(source, target, ctx, table);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        } catch (SQLException | RuntimeException e) {
            finish(projectId, CdcPosition.VERIFY_ERROR, issues.size() + 1,
                    "metadata verification failed: " + messageOf(e), issues);
            return;
        }

        String status = issues.isEmpty() ? CdcPosition.VERIFY_OK
                : CdcPosition.VERIFY_MISMATCH;
        String summary = "checked " + checked + " table(s), " + issues.size()
                + " issue(s)";
        finish(projectId, status, issues.size(), summary, issues);
        log.info("External baseline verification for project {}: {}", projectId, summary);
        if (!issues.isEmpty()) {
            log.warn("External baseline verification for project {} found: {}",
                    projectId, issues);
        }
    }

    private Set<String> coveredTables(Project project, SyncConfig config,
                                      CdcPosition position) throws SQLException {
        Set<String> names = position.externalBaselineTableNames();
        if (!names.isEmpty()) {
            return names;
        }
        if (!position.externalBaselineIsAllTables()) {
            return Set.of();
        }
        SyncContext sourceCtx = contextFactory.buildSourceOnly(project, config);
        try (Connection source = dataSourceManager.getConnection(sourceCtx.getSourceConfig())) {
            Set<String> resolved = new LinkedHashSet<>();
            for (String table : sourceCtx.getSourceReader()
                    .listTableNames(source, sourceCtx.getSourceSchema())) {
                if (config.includesTable(table)) {
                    resolved.add(table);
                }
            }
            return resolved;
        }
    }

    private String verifyTable(Connection source, Connection target, SyncContext ctx,
                               String sourceTable) {
        String targetTable = ctx.getConfig().targetTableName(sourceTable);
        TableMeta sourceMeta;
        try {
            sourceMeta = ctx.getSourceReader()
                    .readTable(source, ctx.getSourceSchema(), sourceTable);
        } catch (SQLException | RuntimeException e) {
            return sourceTable + ": source metadata unavailable (" + messageOf(e) + ")";
        }
        if (sourceMeta == null || sourceMeta.getColumns().isEmpty()) {
            return sourceTable + ": source table missing or has no readable columns";
        }
        try {
            if (!TargetTableVerifier.tableExists(target, ctx.getTargetSchema(), targetTable,
                    ctx.effectiveTargetType(), ctx.targetPreservesIdentifierCase())) {
                return sourceTable + ": target table " + targetTable + " is missing";
            }
            String incompatibility = TargetTableVerifier.verify(target, ctx.getTargetSchema(),
                    targetTable, sourceMeta, ctx.effectiveTargetType(),
                    ctx.targetPreservesIdentifierCase());
            return incompatibility == null ? null : sourceTable + ": " + incompatibility;
        } catch (SQLException | RuntimeException e) {
            return sourceTable + ": target metadata unavailable (" + messageOf(e) + ")";
        }
    }

    private void finish(Long projectId, String status, int issues, String summary,
                        List<String> details) {
        String text = summary;
        if (details != null && !details.isEmpty()) {
            StringBuilder builder = new StringBuilder(summary);
            int limit = Math.min(details.size(), MAX_DETAIL_LINES);
            for (int i = 0; i < limit; i++) {
                builder.append('\n').append(details.get(i));
            }
            if (details.size() > limit) {
                builder.append("\n… and ").append(details.size() - limit).append(" more");
            }
            text = builder.toString();
        }
        String recorded = text;
        positionStore.markExternalBaselineVerification(projectId, status,
                issues, recorded, Instant.now());
        // The status is the contract the card reads. A diagnostic-log failure must not
        // bubble out and make the async catch rewrite a successful OK/MISMATCH as ERROR.
        try {
            projectRepository.findById(projectId).ifPresent(project ->
                    changeLogRepository.save(ChangeLog.of(projectId, ObjectType.PROJECT,
                            project.getName(),
                            CdcPosition.VERIFY_OK.equals(status)
                                    ? ChangeType.INFO : ChangeType.ERROR,
                            "external baseline structure verification: " + recorded)));
        } catch (RuntimeException e) {
            log.warn("Could not write the external baseline verification log for project"
                    + " {}: {}", projectId, e.toString());
        }
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
