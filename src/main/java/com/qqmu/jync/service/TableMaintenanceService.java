package com.qqmu.jync.service;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskRunner;

import lombok.extern.slf4j.Slf4j;

/**
 * Single-table maintenance behind the detail page.
 *
 * <p>Row counts, data sampling and structural compatibility are already covered by the
 * project's 行数核对, 内容校验 and 运行前预检 actions, so this service only owns the one
 * operation those cannot express: reloading a single table.
 *
 * <p>The reload goes through {@link SyncTaskRunner} so it competes for the same project lock
 * as the scheduler and a CDC consumer. It reuses the engine's targeted-repair mode: only that
 * table is read, its target is created when absent, and its own progress/cursor is reset before
 * the table is reloaded. Other tables, structure diffs and deletions are not touched, and a CDC
 * position is never moved.
 */
@Slf4j
@Service
public class TableMaintenanceService {

    private final ProjectRepository projectRepository;
    private final ChangeLogRepository changeLogRepository;
    private final SyncContextFactory contextFactory;
    private final SyncTaskRunner taskRunner;

    public TableMaintenanceService(ProjectRepository projectRepository,
                                   ChangeLogRepository changeLogRepository,
                                   SyncContextFactory contextFactory,
                                   SyncTaskRunner taskRunner) {
        this.projectRepository = projectRepository;
        this.changeLogRepository = changeLogRepository;
        this.contextFactory = contextFactory;
        this.taskRunner = taskRunner;
    }

    /**
     * Reloads one selected table from the source. The project must be stopped and must not
     * fan out; a CDC position is deliberately left where it is.
     *
     * @throws IllegalArgumentException when the project or table does not exist
     * @throws IllegalStateException    when the project's current shape refuses a reload
     */
    public SyncTaskRunner.Outcome reload(Long projectId, String tableName) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("error.table.reload.tableRequired");
        }
        String table = tableName.trim();
        if (project.getTargetKindOrDefault() != SyncTargetKind.JDBC) {
            throw new IllegalStateException("error.table.reload.jdbcOnly");
        }
        if (Boolean.TRUE.equals(project.getEnabled())) {
            throw new IllegalStateException("error.table.reload.stopFirst");
        }
        SyncConfig config;
        try {
            config = contextFactory.parseConfig(project);
            if (config == null) {
                config = new SyncConfig();
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("error.table.reload.config", e);
        }
        if (config.getFanoutTargetDbIds() != null && !config.getFanoutTargetDbIds().isEmpty()) {
            throw new IllegalStateException("error.table.reload.fanout");
        }
        if (!config.isSyncData()) {
            throw new IllegalStateException("error.table.reload.noData");
        }
        if (!config.includesTable(table)) {
            throw new IllegalArgumentException("error.table.reload.notSelected");
        }
        // Resolve the source-side spelling once: the engine's table list is case-insensitive,
        // but the progress/checkpoint rows and the audit trail should carry the selected name.
        String sourceName = selectedName(config, table);
        SyncTaskRunner.Outcome outcome = taskRunner.runTableReload(projectId, sourceName);
        outcome.result().ifPresent(result -> {
            if (result.isSuccess()) {
                // The reload truncates and rewrites the target, so the change log must carry
                // more than the per-batch row counts: without this line the truncation has no
                // explanation next to the inserts. Bookkeeping must not turn a completed
                // reload into an HTTP failure.
                try {
                    changeLogRepository.save(ChangeLog.of(projectId, ObjectType.TABLE,
                            sourceName, ChangeType.INFO,
                            "manual table reload: target truncated and reloaded from the"
                                    + " source (" + (result.getRowsInserted()
                                    + result.getRowsUpdated()) + " row(s) written); the CDC"
                                    + " position was left untouched"));
                } catch (RuntimeException e) {
                    log.warn("Table reload of project {} succeeded but its change-log entry"
                            + " could not be written: {}", projectId, e.toString());
                }
            }
        });
        return outcome;
    }

    private static String selectedName(SyncConfig config, String table) {
        Set<String> names = config.getTables();
        if (names == null || names.isEmpty()) {
            return table;
        }
        for (String name : new LinkedHashSet<>(names)) {
            if (name != null && name.equalsIgnoreCase(table)) {
                return name;
            }
        }
        return table;
    }
}
