package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * The on-demand content audit behind the detail page's button.
 *
 * <p>It audits one named table against the progress row the sync engine already wrote:
 * eligibility (incremental cursor, primary key, initial load done) still applies, so this is
 * a way to run now, not a way to make an incomparable table comparable. Nothing here writes
 * progress — a table that has never synced is skipped instead of gaining a row it did not
 * earn.
 */
@Service
public class ContentAuditRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentAuditRunner.class);

    private final ProjectRepository projectRepository;
    private final SyncProgressRepository progressRepository;
    private final SyncContextFactory contextFactory;
    private final DataSourceManager dataSourceManager;
    private final CursorStrategyResolver cursorResolver;
    private final ContentAuditService contentAudit;

    public ContentAuditRunner(ProjectRepository projectRepository,
                              SyncProgressRepository progressRepository,
                              SyncContextFactory contextFactory,
                              DataSourceManager dataSourceManager,
                              CursorStrategyResolver cursorResolver,
                              ContentAuditService contentAudit) {
        this.projectRepository = projectRepository;
        this.progressRepository = progressRepository;
        this.contextFactory = contextFactory;
        this.dataSourceManager = dataSourceManager;
        this.cursorResolver = cursorResolver;
        this.contentAudit = contentAudit;
    }

    /**
     * Audits one table, ignoring the scheduled interval and cycle budget but not eligibility.
     *
     * @throws IllegalArgumentException when the project does not exist
     */
    public TableContentAudit auditProject(Long projectId, String tableName) {
        long started = System.currentTimeMillis();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        if (tableName == null || tableName.isBlank()) {
            return TableContentAudit.skipped("?", "no-table", elapsed(started));
        }
        String table = tableName.trim();

        SyncProgress progress = progressRepository
                .findByProjectIdAndObjectTypeAndObjectName(projectId, ObjectType.TABLE, table)
                .orElse(null);
        if (progress == null) {
            // Before the first cycle there is no delivered data to compare against, and
            // writing a progress row here would make the table look synced.
            return TableContentAudit.skipped(table, "never-synced", elapsed(started));
        }

        SyncContext ctx;
        try {
            ctx = contextFactory.build(project, "content-audit", null);
        } catch (RuntimeException e) {
            return TableContentAudit.error(table, messageOf(e), elapsed(started));
        }

        try (Connection sourceConn = dataSourceManager.getConnectionOrDefer(ctx.getSourceConfig());
             Connection targetConn = dataSourceManager.getConnectionOrDefer(ctx.getTargetConfig())) {
            TableMeta tableMeta = ctx.getSourceReader().readTable(
                    sourceConn, ctx.getSourceSchema(), table);
            CursorStrategy strategy = cursorResolver.resolve(tableMeta, ctx.getConfig());
            TableContentAudit result = contentAudit.auditNow(sourceConn, targetConn, tableMeta,
                    strategy, progress, ctx);
            record(ctx, result);
            return result;
        } catch (SQLException | RuntimeException e) {
            // The reader and the connection pool can fail with unchecked exceptions too. The
            // button reports the table's audit status, so they belong in an ERROR result
            // rather than in a 500 from the controller.
            log.debug("Manual content audit of {} failed: {}", table, e.toString());
            return TableContentAudit.error(table, e.getMessage(), elapsed(started));
        }
    }

    private void record(SyncContext ctx, TableContentAudit result) {
        try {
            contentAudit.record(ctx, result);
        } catch (RuntimeException e) {
            // Recording is a side effect; failing it must not hide the report the caller
            // just asked for.
            log.warn("Could not record the manual content audit of {}: {}",
                    result == null ? "?" : result.table(), e.toString());
        }
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private static String messageOf(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }
}
