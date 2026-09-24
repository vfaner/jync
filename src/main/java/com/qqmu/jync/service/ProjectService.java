package com.qqmu.jync.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.quartz.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** Project lifecycle: create, configure, start, stop and inspect. */
@Service
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final DatabaseConfigRepository databaseConfigRepository;
    private final SyncProgressRepository progressRepository;
    private final ChangeLogRepository changeLogRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final MetadataSnapshotService snapshotService;
    private final SyncContextFactory contextFactory;
    private final SyncScheduler scheduler;
    private final SyncTaskRunner taskRunner;
    private final SyncTaskStore taskStore;
    private final SyncEngine syncEngine;

    public ProjectService(ProjectRepository projectRepository,
                          DatabaseConfigRepository databaseConfigRepository,
                          SyncProgressRepository progressRepository,
                          ChangeLogRepository changeLogRepository,
                          DataSourceManager dataSourceManager,
                          MetadataReaderFactory readerFactory,
                          MetadataSnapshotService snapshotService,
                          SyncContextFactory contextFactory,
                          SyncScheduler scheduler,
                          SyncTaskRunner taskRunner,
                          SyncTaskStore taskStore,
                          SyncEngine syncEngine) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.progressRepository = progressRepository;
        this.changeLogRepository = changeLogRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.snapshotService = snapshotService;
        this.contextFactory = contextFactory;
        this.scheduler = scheduler;
        this.taskRunner = taskRunner;
        this.taskStore = taskStore;
        this.syncEngine = syncEngine;
    }

    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    public Optional<Project> findById(Long id) {
        return projectRepository.findById(id);
    }

    public long count() {
        return projectRepository.count();
    }

    /** Creates or updates a project. Rescheduling follows the enabled flag automatically. */
    @Transactional
    public Project save(Project project) {
        if (project.getName() == null || project.getName().isBlank()) {
            throw new IllegalArgumentException("error.project.name.required");
        }
        project.setName(project.getName().trim());
        projectRepository.findByName(project.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(project.getId())) {
                throw new IllegalArgumentException("error.project.name.duplicate");
            }
        });
        if (project.getSourceDbId() != null && project.getSourceDbId().equals(project.getTargetDbId())) {
            // Syncing a database onto itself would loop changes back onto the source.
            throw new IllegalArgumentException("error.project.same.database");
        }

        boolean isNew = project.getId() == null;
        if (!isNew) {
            // The edit form only carries the descriptive fields; enabled, syncConfig and
            // createdAt live on the row, so a stale tab submitting this form must neither
            // stop a running project nor null out columns the form never contained.
            // Start/stop go through their own methods below and are unaffected.
            Project existing = require(project.getId());
            project.setEnabled(existing.getEnabled());
            project.setSyncConfig(existing.getSyncConfig());
            project.setCreatedAt(existing.getCreatedAt());
        }
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);

        // Keep the schedule consistent with the flag on every save, not just on start/stop.
        scheduler.reschedule(saved);
        log.info("{} project '{}'", isNew ? "Created" : "Updated", saved.getName());
        return saved;
    }

    /** Persists just the sync settings for a project. */
    @Transactional
    public Project saveConfig(Long projectId, SyncConfig config) {
        Project project = require(projectId);
        String cron = config.getCronExpression();
        // Blank means "use interval mode", so only a non-blank value can be wrong. Checking
        // here rather than in Quartz keeps one bad field from becoming a 500 that rolls back
        // every other setting the same form submitted.
        if (cron != null && !cron.isBlank() && !CronExpression.isValidExpression(cron.trim())) {
            throw new IllegalArgumentException("error.project.cron.invalid");
        }
        project.setSyncConfig(contextFactory.serializeConfig(config));
        Project saved = projectRepository.save(project);
        // Interval or cron may have changed, so rebuild the trigger.
        scheduler.reschedule(saved);
        return saved;
    }

    public SyncConfig loadConfig(Project project) {
        return contextFactory.parseConfig(project);
    }

    /** Enables the project and starts polling, running one cycle immediately. */
    @Transactional
    public void start(Long projectId) {
        Project project = require(projectId);
        if (project.getSourceDbId() == null || project.getTargetDbId() == null) {
            throw new IllegalStateException("error.project.endpoints.required");
        }
        project.setEnabled(true);
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        scheduler.schedule(saved);
        // Fire once now so the user sees immediate feedback rather than waiting a full interval.
        scheduler.triggerNow(projectId);
        log.info("Started sync for project '{}'", saved.getName());
    }

    /** Disables the project and removes its schedule. An in-flight cycle finishes on its own. */
    @Transactional
    public void stop(Long projectId) {
        Project project = require(projectId);
        project.setEnabled(false);
        projectRepository.save(project);
        scheduler.unschedule(projectId);
        log.info("Stopped sync for project '{}'", project.getName());
    }

    /** Runs one cycle on demand, contending for the same lock the scheduler uses. */
    public SyncTaskRunner.Outcome syncNow(Long projectId) {
        require(projectId);
        return taskRunner.runOnce(projectId);
    }

    /**
     * Clears all cursors and snapshots so the next run reloads everything.
     *
     * <p>Refuses while the project is enabled: resetting state underneath a running sync would
     * race with the cycle currently advancing those same cursors.
     */
    @Transactional
    public void resetProgress(Long projectId) {
        Project project = require(projectId);
        if (Boolean.TRUE.equals(project.getEnabled())) {
            throw new IllegalStateException("error.project.stop.before.reset");
        }
        syncEngine.resetProject(projectId);
    }

    @Transactional
    public void delete(Long projectId) {
        Project project = require(projectId);
        scheduler.unschedule(projectId);
        taskStore.deleteForProject(projectId);
        progressRepository.deleteByProjectId(projectId);
        snapshotService.deleteAllForProject(projectId);
        changeLogRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
        log.info("Deleted project '{}' and all its sync state", project.getName());
    }

    /**
     * Lists the objects available in a project's source database, for the selection UI.
     *
     * @throws IllegalStateException when the source cannot be reached, so the UI can say why
     */
    public SourceObjects listSourceObjects(Long projectId) {
        Project project = require(projectId);
        if (project.getSourceDbId() == null) {
            throw new IllegalStateException("error.project.source.required");
        }
        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));

        MetadataReader reader = readerFactory.forType(source.getType());
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String schema = source.getSchemaName() != null && !source.getSchemaName().isBlank()
                    ? source.getSchemaName()
                    : reader.resolveDefaultSchema(conn);
            SourceObjects objects = new SourceObjects();
            objects.schema = schema;
            objects.tables = reader.listTableNames(conn, schema);
            objects.views = reader.listViewNames(conn, schema);
            objects.procedures = reader.listProcedureNames(conn, schema);
            return objects;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read source objects: " + e.getMessage(), e);
        }
    }

    /**
     * Columns of one source table, so the user can pick a cursor column explicitly.
     *
     * <p>Only comparable types are offered: a cursor must support {@code >} and {@code MAX()}.
     */
    public List<String> listCursorCandidates(Long projectId, String tableName) {
        Project project = require(projectId);
        if (project.getSourceDbId() == null) {
            // Endpoints are optional until start(), so findById(null) would otherwise leak
            // its internal English error to the caller.
            throw new IllegalStateException("error.project.source.required");
        }
        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
        MetadataReader reader = readerFactory.forType(source.getType());
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String schema = source.getSchemaName() != null && !source.getSchemaName().isBlank()
                    ? source.getSchemaName() : reader.resolveDefaultSchema(conn);
            var table = reader.readTable(conn, schema, tableName);
            List<String> candidates = new ArrayList<>();
            for (var column : table.getColumns()) {
                if (com.qqmu.jync.service.monitor.CursorStrategy.isTemporalType(column.getJdbcType())
                        || com.qqmu.jync.service.monitor.CursorStrategy
                            .isIntegralType(column.getJdbcType())) {
                    candidates.add(column.getName());
                }
            }
            return candidates;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read columns of " + tableName + ": "
                    + e.getMessage(), e);
        }
    }

    /** Selects every source object, matching the default "all tables" behaviour. */
    public SyncConfig selectAll(Long projectId) {
        SourceObjects objects = listSourceObjects(projectId);
        Project project = require(projectId);
        SyncConfig config = loadConfig(project);
        config.setTables(new LinkedHashSet<>(objects.tables));
        config.setViews(new LinkedHashSet<>(objects.views));
        config.setProcedures(new LinkedHashSet<>(objects.procedures));
        return config;
    }

    public Optional<SyncTask> findTask(Long projectId) {
        return taskStore.find(projectId);
    }

    public List<com.qqmu.jync.model.SyncProgress> findProgress(Long projectId) {
        return progressRepository.findByProjectId(projectId);
    }

    public boolean isScheduled(Long projectId) {
        return scheduler.isScheduled(projectId);
    }

    private Project require(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("error.project.not.found"));
    }

    /** Objects discovered in a source database. */
    @Getter
    public static class SourceObjects {
        private String schema;
        private List<String> tables = new ArrayList<>();
        private List<String> views = new ArrayList<>();
        private List<String> procedures = new ArrayList<>();
    }
}
