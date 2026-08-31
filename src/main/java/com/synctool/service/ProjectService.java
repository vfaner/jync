package com.synctool.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.dto.SyncConfig;
import com.synctool.dto.SyncResult;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.Project;
import com.synctool.model.SyncTask;
import com.synctool.repository.ChangeLogRepository;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.repository.SyncProgressRepository;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.service.metadata.MetadataReader;
import com.synctool.service.metadata.MetadataReaderFactory;
import com.synctool.service.metadata.MetadataSnapshotService;
import com.synctool.service.sync.SyncEngine;
import com.synctool.service.task.SyncContextFactory;
import com.synctool.service.task.SyncScheduler;
import com.synctool.service.task.SyncTaskRunner;
import com.synctool.service.task.SyncTaskStore;

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
    public Optional<SyncResult> syncNow(Long projectId) {
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
        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
        MetadataReader reader = readerFactory.forType(source.getType());
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String schema = source.getSchemaName() != null && !source.getSchemaName().isBlank()
                    ? source.getSchemaName() : reader.resolveDefaultSchema(conn);
            var table = reader.readTable(conn, schema, tableName);
            List<String> candidates = new ArrayList<>();
            for (var column : table.getColumns()) {
                if (com.synctool.service.monitor.CursorStrategy.isTemporalType(column.getJdbcType())
                        || com.synctool.service.monitor.CursorStrategy
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

    public List<com.synctool.model.SyncProgress> findProgress(Long projectId) {
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
