package com.qqmu.jync.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.quartz.CronExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
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
    private final ChunkCheckpointRepository checkpointRepository;
    private final DataSourceManager dataSourceManager;
    private final MetadataReaderFactory readerFactory;
    private final MetadataSnapshotService snapshotService;
    private final SyncContextFactory contextFactory;
    private final SyncScheduler scheduler;
    private final SyncTaskRunner taskRunner;
    private final SyncTaskStore taskStore;
    private final SyncEngine syncEngine;
    private final SyncLockService lockService;
    private final AdminAuditService auditService;

    public ProjectService(ProjectRepository projectRepository,
                          DatabaseConfigRepository databaseConfigRepository,
                          SyncProgressRepository progressRepository,
                          ChangeLogRepository changeLogRepository,
                          ChunkCheckpointRepository checkpointRepository,
                          DataSourceManager dataSourceManager,
                          MetadataReaderFactory readerFactory,
                          MetadataSnapshotService snapshotService,
                          SyncContextFactory contextFactory,
                          SyncScheduler scheduler,
                          SyncTaskRunner taskRunner,
                          SyncTaskStore taskStore,
                          SyncEngine syncEngine,
                          SyncLockService lockService,
                          AdminAuditService auditService) {
        this.projectRepository = projectRepository;
        this.databaseConfigRepository = databaseConfigRepository;
        this.progressRepository = progressRepository;
        this.changeLogRepository = changeLogRepository;
        this.checkpointRepository = checkpointRepository;
        this.dataSourceManager = dataSourceManager;
        this.readerFactory = readerFactory;
        this.snapshotService = snapshotService;
        this.contextFactory = contextFactory;
        this.scheduler = scheduler;
        this.taskRunner = taskRunner;
        this.taskStore = taskStore;
        this.syncEngine = syncEngine;
        this.lockService = lockService;
        this.auditService = auditService;
    }

    public List<Project> findAll() {
        return projectRepository.findAll();
    }

    /**
     * One page of projects for the list view, ordered by id so pagination is stable.
     * Loading everything and slicing in memory meant the list page read the whole table
     * (and the database re-sorted it) on every render.
     */
    public List<Project> findPage(int offset, int size) {
        return projectRepository
                .findAll(PageRequest.of(offset / size, size, Sort.by("id")))
                .getContent();
    }

    public Optional<Project> findById(Long id) {
        return projectRepository.findById(id);
    }

    public long count() {
        return projectRepository.count();
    }

    /**
     * Runs {@code action} only after the surrounding transaction commits. Scheduling work
     * must wait for commit: a Quartz trigger that fired pre-commit could read stale or
     * absent rows, and a rolled-back save would leave a live schedule with no project.
     * Outside a transaction the action runs immediately.
     *
     * <p>{@code actionName} names the follow-up for logs: Spring's afterCommit dispatcher
     * would otherwise swallow a failure with only a framework line, leaving an
     * {@code enabled=true} row with no Quartz job and no way to diagnose it.
     */
    private void afterCommit(Long projectId, String actionName, Runnable action) {
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Post-commit action '{}' failed for project {}; the transaction "
                        + "committed but its follow-up work may be missing",
                        actionName, projectId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    /**
     * Re-schedules an enabled project if the current transaction rolls back. Used only by
     * {@link #delete}, which unschedules <em>before</em> taking the lock (so the purge runs
     * against a quiet project) and therefore needs a rollback path back to a live schedule.
     */
    private void restoreScheduleOnRollback(Long projectId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // STATUS_UNKNOWN needs the same path: whether the delete actually committed
                // is undecided, but findById answers it — a missing row means nothing to
                // restore, a present one needs its schedule back.
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK
                        && status != TransactionSynchronization.STATUS_UNKNOWN) {
                    return;
                }
                projectRepository.findById(projectId)
                        .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                        .ifPresent(p -> {
                            try {
                                // unschedule() already committed STOPPED in its own
                                // transaction; flip the status back with the schedule.
                                taskStore.markRunning(projectId);
                                scheduler.schedule(p);
                            } catch (RuntimeException e) {
                                log.error("Failed to restore the schedule for project {} after "
                                        + "transaction outcome {}", projectId, status, e);
                            }
                        });
            }
        });
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
        // Inside the same transaction: the audit row commits with the change it describes
        // and rolls back with it, so the trail never claims a save that did not happen.
        auditService.record(AuditAction.PROJECT_SAVE,
                (isNew ? "Created" : "Updated") + " project '" + saved.getName() + "'");

        // Keep the schedule consistent with the flag on every save, not just on start/stop.
        afterCommit(saved.getId(), "reschedule", () -> scheduler.reschedule(saved));
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
        // Refuse a mapping that points two source tables at one target before it is persisted:
        // running it would interleave rows and let one source's truncate wipe the other's.
        List<String> mappingConflicts = config.getTables().isEmpty()
                ? config.tableMappingConflicts(null)
                : config.tableMappingConflicts(config.getTables());
        if (!mappingConflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "error.project.tablemapping.duplicate:" + mappingConflicts.get(0));
        }
        project.setSyncConfig(contextFactory.serializeConfig(config));
        Project saved = projectRepository.save(project);
        // Interval or cron may have changed, so rebuild the trigger.
        afterCommit(saved.getId(), "reschedule", () -> scheduler.reschedule(saved));
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
        afterCommit(projectId, "start", () -> {
            scheduler.schedule(saved);
            // Fire once now so the user sees immediate feedback rather than waiting a full interval.
            scheduler.triggerNow(projectId);
        });
        log.info("Started sync for project '{}'", saved.getName());
    }

    /** Disables the project and removes its schedule. An in-flight cycle finishes on its own. */
    @Transactional
    public void stop(Long projectId) {
        Project project = require(projectId);
        project.setEnabled(false);
        projectRepository.save(project);
        afterCommit(projectId, "stop", () -> scheduler.unschedule(projectId));
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

    /**
     * Admin override: drops a project's sync lease whoever holds it.
     *
     * <p>Exists for the wedge TTL expiry already covers but slowly — a crashed or hung owner
     * keeps its lease until it expires, and meanwhile start/sync-now/delete all refuse with
     * "a cycle is still running". Forcing is safe in the same way expiry is: the holder
     * notices its lost lease at the next renewal and aborts before further writes.
     *
     * <p>The action is audited in the change log with the previous holder, its lease expiry
     * and the acting admin, because it can stop a cycle that was still alive.
     *
     * @throws IllegalStateException when the project does not exist, or no lease is held
     *                               (nothing to force — the UI should not offer the button)
     */
    @Transactional
    public void forceUnlock(Long projectId) {
        Project project = require(projectId);
        SyncTask task = taskStore.find(projectId).orElse(null);
        String previousOwner = task == null ? null : task.getLockOwner();
        if (previousOwner == null) {
            throw new IllegalStateException("error.project.lock.not.held");
        }
        String previousExpiry = task.getLockExpiresAt() == null ? "unknown"
                : task.getLockExpiresAt().toString();
        boolean cleared = lockService.forceUnlock(projectId);
        String actor = currentUsername();
        String detail = "Sync lock force-released by " + actor
                + " (previous holder " + previousOwner + ", lease until " + previousExpiry + ")";
        ChangeLog entry = ChangeLog.of(projectId, ObjectType.PROJECT, project.getName(),
                ChangeType.INFO, detail);
        entry.setSuccess(cleared);
        changeLogRepository.save(entry);
        // The change log for this project can itself be cleared later; the admin audit
        // trail is append-only, so the force-unlock record outlives it.
        auditService.record(AuditAction.PROJECT_FORCE_UNLOCK, detail);
        log.warn("Project '{}': {}", project.getName(), detail);
    }

    /** The signed-in username for audit entries, or "unknown" outside a request context. */
    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "unknown" : auth.getName();
    }

    /**
     * Deletes a project together with all of its sync state.
     *
     * <p>Takes the project's sync lock first: a cycle still in flight would otherwise
     * re-create progress and snapshot rows after this method cleared them (those tables
     * carry no foreign key to the project), leaving orphan state nobody can see or clean.
     * Holding the lock also blocks syncNow and the Quartz job for the duration, and
     * unscheduling first stops new fires, so the purge runs against a quiet project.
     */
    @Transactional
    public void delete(Long projectId) {
        Project project = require(projectId);
        scheduler.unschedule(projectId);
        // Delete can still roll back (lock busy, a failing delete, ...). Restore an enabled
        // project's schedule on rollback rather than leaving it silently unmonitored.
        restoreScheduleOnRollback(projectId);
        // tryAcquire returns null when the lock is unavailable; a try-with-resources on a
        // null resource simply skips close(), so the refusal is just an early throw.
        try (SyncLockService.LockHandle lock = lockService.tryAcquire(projectId)) {
            if (lock == null) {
                throw new IllegalStateException("error.project.sync.in.progress");
            }
            taskStore.deleteForProject(projectId);
            syncEngine.evictProjectCaches(projectId);
            lockService.evictProject(projectId);
            progressRepository.deleteByProjectId(projectId);
            snapshotService.deleteAllForProject(projectId);
            changeLogRepository.deleteByProjectId(projectId);
            projectRepository.deleteById(projectId);
            auditService.record(AuditAction.PROJECT_DELETE,
                    "Deleted project '" + project.getName() + "' and all its sync state");
        }
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
            // Keep the driver's raw detail server-side; callers only surface the message key.
            log.debug("Cannot read source objects for project '{}': {}",
                    project.getName(), e.toString());
            throw new IllegalStateException("error.source.objects.read.failed", e);
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
            // Keep the driver's raw detail server-side; callers only surface the message key.
            log.debug("Cannot read columns of '{}' for project '{}': {}",
                    tableName, project.getName(), e.toString());
            throw new IllegalStateException("error.source.columns.read.failed", e);
        }
    }

    public Optional<SyncTask> findTask(Long projectId) {
        return taskStore.find(projectId);
    }

    /** Batch variant of {@link #findTask} for list pages: one query for the whole page. */
    public Map<Long, SyncTask> findTasks(java.util.Collection<Long> projectIds) {
        return taskStore.findByProjectIds(projectIds);
    }

    public List<com.qqmu.jync.model.SyncProgress> findProgress(Long projectId) {
        return progressRepository.findByProjectId(projectId);
    }

    /**
     * In-flight chunked full-load state per table, for the project detail page.
     *
     * <p>Checkpoint rows only exist while a load is running or lies interrupted (a completed
     * load clears them), so an entry here means exactly "this table's initial full load has
     * not finished"; the counts tell how far it got and whether it will resume.
     */
    public Map<String, FullLoadProgress> findFullLoadProgress(Long projectId) {
        Map<String, List<ChunkCheckpoint>> byTable = new LinkedHashMap<>();
        for (ChunkCheckpoint c : checkpointRepository.findByProjectId(projectId)) {
            byTable.computeIfAbsent(c.getTableName(), k -> new ArrayList<>()).add(c);
        }
        Map<String, FullLoadProgress> result = new LinkedHashMap<>();
        byTable.forEach((table, rows) -> result.put(table, FullLoadProgress.of(rows)));
        return result;
    }

    /** One table's chunked full-load state, derived from its checkpoint rows. */
    @Getter
    public static class FullLoadProgress {
        /** Chunks committed on the target so far (across all ranges). */
        private final int chunksDone;
        /** Key ranges the load has touched; more than one means a parallel load. */
        private final int rangesSeen;
        /** Ranges already finished; a resumed load skips them entirely. */
        private final int rangesDone;

        FullLoadProgress(int chunksDone, int rangesSeen, int rangesDone) {
            this.chunksDone = chunksDone;
            this.rangesSeen = rangesSeen;
            this.rangesDone = rangesDone;
        }

        public static FullLoadProgress of(List<ChunkCheckpoint> rows) {
            int chunks = 0;
            int rangesDone = 0;
            int rangesSeen = 0;
            for (ChunkCheckpoint c : rows) {
                if (ChunkCheckpoint.STATUS_RANGE_DONE.equals(c.getStatus())) {
                    rangesDone++;
                } else {
                    chunks++;
                }
                int range = c.getRangeIndex() == null ? 0 : c.getRangeIndex();
                rangesSeen = Math.max(rangesSeen, range + 1);
            }
            return new FullLoadProgress(chunks, rangesSeen, rangesDone);
        }

        /** True when the load split the key span across parallel workers. */
        public boolean isParallel() {
            return rangesSeen > 1;
        }
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
