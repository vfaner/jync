package com.qqmu.jync.service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.quartz.CronExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.PgLogicalCdcConsumer;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
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

    /** Match the NOT NULL VARCHAR limits on the project row so overlong input is a field
     *  error rather than a H2 22001 constraint failure rendered as a generic 500. */
    private static final int NAME_MAX_LENGTH = 128;
    private static final int DESCRIPTION_MAX_LENGTH = 512;

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
    private final CdcConsumerManager cdcManager;
    private final SyncStateWriter stateWriter;
    private final CdcPositionStore positionStore;
    private final SyncProperties properties;

    /**
     * Lenient reader for the persisted sync_config JSON, used only to recover the CURRENT
     * capture strategy of a row before it is overwritten. Mirrors
     * {@link CdcConsumerManager#strategyOf} semantics: unknown JSON fields are tolerated
     * and only truly corrupt or absent config degrades to POLLING. The leniency is
     * belt-and-braces — {@code SyncConfig} already carries
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} — but this mapper is configured
     * exactly like the manager's so a future nested type without the annotation cannot
     * skew the two reads: a skew (manager sees BINLOG_CDC, this parse degrades to POLLING)
     * would misfire the whole C2 switch sequence on an unchanged strategy. The manager
     * bean cannot be reused here because saveConfig needs the parse to behave identically
     * under unit-test mocks of the manager.
     */
    private static final ObjectMapper STRATEGY_JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
                          AdminAuditService auditService,
                          CdcConsumerManager cdcManager,
                          SyncStateWriter stateWriter,
                          CdcPositionStore positionStore,
                          SyncProperties properties) {
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
        this.cdcManager = cdcManager;
        this.stateWriter = stateWriter;
        this.positionStore = positionStore;
        this.properties = properties;
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
        if (project.getName().length() > NAME_MAX_LENGTH) {
            // Left to H2 this arrives as a raw 22001 DataIntegrityViolation → generic 500.
            throw new IllegalArgumentException("error.project.name.too.long");
        }
        if (project.getDescription() != null && project.getDescription().length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("error.project.description.too.long");
        }
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
        boolean endpointsChanged = false;
        if (isNew) {
            // The create form carries no start switch: projects always start life stopped and
            // are later enabled through the explicit start action. A bound enabled=true from
            // a crafted POST must not survive into the row (it would schedule immediately).
            project.setEnabled(false);
        }
        if (!isNew) {
            // enabled, syncConfig and createdAt are not the form's to set: a stale tab
            // submitting this form must neither stop a running project nor null out columns
            // the form never contained. Start/stop go through their own methods below.
            Project existing = require(project.getId());
            endpointsChanged = !Objects.equals(project.getSourceDbId(), existing.getSourceDbId())
                    || !Objects.equals(project.getTargetDbId(), existing.getTargetDbId());
            if (endpointsChanged && isRunning(existing)) {
                // The running cycle (or live CDC consumer) holds the old endpoints; switching
                // them mid-flight would read one database and write another within a single
                // run. Stop the project first.
                throw new IllegalStateException("error.project.edit.running");
            }
            project.setEnabled(existing.getEnabled());
            project.setSyncConfig(existing.getSyncConfig());
            project.setCreatedAt(existing.getCreatedAt());
        }
        Project saved = projectRepository.save(project);
        taskStore.ensureTask(saved);
        if (endpointsChanged) {
            // Cursors, chunk checkpoints, structure snapshots and the CDC position all
            // describe the OLD source/target pair. Carried into the new pair they make the
            // next run start from another database's watermark, silently skipping rows (and
            // a new target would never get its history). Clear them so the next start is a
            // full reload — the same state resetProgress() performs, just implied by the
            // endpoint change instead of requested explicitly.
            syncEngine.resetProject(saved.getId());
            checkpointRepository.deleteByProjectId(saved.getId());
            positionStore.delete(saved.getId());
            log.info("Project '{}' endpoints changed; sync state cleared for a full reload",
                    saved.getName());
        }
        // Inside the same transaction: the audit row commits with the change it describes
        // and rolls back with it, so the trail never claims a save that did not happen.
        auditService.record(AuditAction.PROJECT_SAVE,
                (isNew ? "Created" : "Updated") + " project '" + saved.getName() + "'");

        // Keep the schedule consistent with the flag on every save, not just on start/stop.
        afterCommit(saved.getId(), "reschedule", () -> scheduler.reschedule(saved));
        log.info("{} project '{}'", isNew ? "Created" : "Updated", saved.getName());
        return saved;
    }

    /** Persists just the sync settings for a project, leaving the capture strategy as-is. */
    @Transactional
    public Project saveConfig(Long projectId, SyncConfig config) {
        return saveConfig(projectId, config, null);
    }

    /**
     * Persists the sync settings and, when {@code captureStrategy} names a strategy other
     * than the row's current one, runs the C2 strategy-switch flow after commit.
     *
     * <p>The CDC-on-non-MySQL gate throws BEFORE anything is persisted: the surrounding
     * transaction rolls back and none of the form's settings are saved — a rejected
     * strategy must not leave a half-written config behind.
     *
     * @param captureStrategy a {@link CaptureStrategy} name, or null/blank to keep the
     *                        stored strategy unchanged (the pre-v1.5.0 two-arg behavior)
     */
    @Transactional
    public Project saveConfig(Long projectId, SyncConfig config, String captureStrategy) {
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
        // Read the OLD strategy off the row before it is overwritten below.
        CaptureStrategy oldStrategy = parseStrategy(project.getSyncConfig());
        CaptureStrategy requested = null;
        if (captureStrategy != null && !captureStrategy.isBlank()) {
            try {
                requested = CaptureStrategy.valueOf(captureStrategy.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("error.project.strategy.invalid");
            }
            // The endpoint is resolved for every explicit strategy submission, so the CDC
            // gate always checks against the real source type (strict == MYSQL: MariaDB
            // and the MySQL-wire compatibles speak a different binlog dialect).
            DatabaseConfig source = project.getSourceDbId() == null ? null
                    : databaseConfigRepository.findById(project.getSourceDbId()).orElse(null);
            if (requested == CaptureStrategy.BINLOG_CDC
                    && (source == null || source.getType() != DatabaseType.MYSQL)) {
                throw new IllegalArgumentException("error.project.cdc.not.mysql");
            }
            if (requested == CaptureStrategy.LOGMINER_CDC
                    && (source == null || source.getType() != DatabaseType.ORACLE)) {
                throw new IllegalArgumentException("error.project.cdc.not.oracle");
            }
            if (requested == CaptureStrategy.PG_LOGICAL_CDC
                    && (source == null || source.getType() != DatabaseType.POSTGRESQL)) {
                throw new IllegalArgumentException("error.project.cdc.not.postgres");
            }
            if (requested == CaptureStrategy.SQLSERVER_CDC
                    && (source == null || source.getType() != DatabaseType.SQLSERVER)) {
                throw new IllegalArgumentException("error.project.cdc.not.sqlserver");
            }
            config.setCaptureStrategy(requested);
        }
        // Table selection and name mapping are topology: changing them under a running
        // cycle makes it copy tables the old schedule never promised, and per-table cursors
        // are keyed by the OLD name. A strategy switch is exempt — that flow stops the
        // engine and clears its cursors in both directions by design.
        boolean switching = requested != null && requested != oldStrategy;
        if (!switching && mappingChanged(parseConfigLenient(project.getSyncConfig()), config)
                && isRunning(project)) {
            throw new IllegalStateException("error.project.mapping.running");
        }
        project.setSyncConfig(contextFactory.serializeConfig(config));
        Project saved = projectRepository.save(project);
        if (requested != null && requested != oldStrategy) {
            CaptureStrategy from = oldStrategy;
            CaptureStrategy to = requested;
            // Both trail rows are written INSIDE the transaction, same rule as the
            // PROJECT_SAVE audit above: they commit with the switch they describe and roll
            // back with it. They cannot live at the end of the afterCommit sequence —
            // record()/save() there would join the already-committed transaction and the
            // rows would silently vanish (observed live: audit missing after the C2
            // sequence ran), and a consumer-start failure would swallow the trail too.
            auditService.record(AuditAction.PROJECT_CAPTURE_SWITCH,
                    "project " + saved.getId() + ": " + from + " -> " + to);
            changeLogRepository.save(ChangeLog.of(saved.getId(), ObjectType.PROJECT,
                    saved.getName(), ChangeType.UPDATE,
                    "capture strategy: " + from + " -> " + to));
            log.info("Project '{}': capture strategy switched, {}", saved.getName(),
                    from + " -> " + to);
            afterCommit(projectId, "capture-strategy-switch",
                    () -> applyStrategySwitch(saved, to));
        } else {
            // Interval or cron may have changed, so rebuild the trigger.
            afterCommit(saved.getId(), "reschedule", () -> scheduler.reschedule(saved));
        }
        return saved;
    }

    /**
     * The runtime side of the C2 switch sequences, run after commit. Both directions
     * clear the polling cursors; everything else is directional:
     *
     * <ul>
     *   <li>→ BINLOG_CDC: drop the Quartz job, invalidate the old binlog position (the
     *       consumer re-baselines with a chunked full load), lift any polling-era ERROR
     *       hat that would silently block the start (Task 13: ensureStarted skips parked
     *       projects), then start the consumer.</li>
     *   <li>BINLOG_CDC →: stop the consumer FIRST and let the join complete, so the old
     *       stream can no longer write (Review Focus #5); then rebuild the polling
     *       schedule. The cdc_position row is KEPT (C2) as informational history only —
     *       it is NOT a resume point: every switch back to BINLOG_CDC deletes the
     *       position and re-baselines (first bullet). The cleared cursors mean the next
     *       polling rounds re-resolve cursor columns and run one full alignment pass
     *       before increments resume.</li>
     * </ul>
     *
     * <p>The audit + change-log rows are NOT written here: they commit with the strategy
     * change itself inside {@link #saveConfig(Long, SyncConfig, String)} (see the comment
     * there — afterCommit writes would ride the completed transaction and vanish).
     */
    private void applyStrategySwitch(Project project, CaptureStrategy to) {
        Long projectId = project.getId();
        if (to.isLogCdc()) {
            // BINLOG_CDC 与 LOGMINER_CDC 的切换序列完全一致:删 Quartz、清 cursor、
            // 删位点(下一个消费者无论哪种都从新锚点重跑基线)、摘 ERROR 帽、起消费者。
            scheduler.unschedule(projectId);
            stateWriter.clearCursor(projectId);
            positionStore.delete(projectId);
            taskStore.markCdcRunning(projectId);
            cdcManager.ensureStarted(project);
        } else {
            cdcManager.ensureStopped(projectId);
            stateWriter.clearCursor(projectId);
            scheduler.reschedule(project);
        }
    }

    /**
     * A project counts as running while its durable enabled flag is set, or while a live
     * cycle still holds its lease. An expired lease is not running — the holder is gone and
     * the documented remedy is force-unlock/TTL reclaim, not an edit blocked forever.
     */
    public boolean isRunning(Project project) {
        if (Boolean.TRUE.equals(project.getEnabled())) {
            return true;
        }
        SyncTask task = taskStore.find(project.getId()).orElse(null);
        if (task == null || task.getLockOwner() == null) {
            return false;
        }
        return task.getLockExpiresAt() == null
                || task.getLockExpiresAt().isAfter(Instant.now());
    }

    /** Full config parse of the persisted JSON; null when absent or unreadable. */
    private static SyncConfig parseConfigLenient(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return STRATEGY_JSON.readValue(json, SyncConfig.class);
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable sync_config while comparing table mappings: {}",
                    e.getMessage());
            return null;
        }
    }

    /** Table selection or name mapping differs from what the row last persisted. */
    private static boolean mappingChanged(SyncConfig previous, SyncConfig next) {
        Set<String> oldTables = previous == null || previous.getTables() == null
                ? Set.of() : previous.getTables();
        Set<String> newTables = next.getTables() == null ? Set.of() : next.getTables();
        Map<String, String> oldNames = previous == null || previous.getTableNameMapping() == null
                ? Map.of() : previous.getTableNameMapping();
        Map<String, String> newNames = next.getTableNameMapping() == null
                ? Map.of() : next.getTableNameMapping();
        return !oldTables.equals(newTables) || !oldNames.equals(newNames);
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
            if (cdcManager.strategyOf(saved).isLogCdc()) {
                // CDC:启动长驻消费者(抢租约→基线→续流),不进 Quartz、不 triggerNow。
                cdcManager.ensureStarted(saved);
                return;
            }
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
        afterCommit(projectId, "stop", () -> {
            if (cdcManager.strategyOf(project).isLogCdc()) {
                // CDC:停消费者并同步 join(返回后旧流绝不再写)。轮询分支的状态回落藏在
                // unschedule 里,CDC 分支必须在这里自己补:消费者管理器只会把状态抬到
                // RUNNING(markCdcRunning),不补这一步干净停机后 task 行将永远是 RUNNING,
                // 读它的看板统计/后续判定会报一个没人调度的项目在跑。
                cdcManager.ensureStopped(projectId);
                taskStore.markStopped(projectId);
                return;
            }
            scheduler.unschedule(projectId);
        });
        log.info("Stopped sync for project '{}'", project.getName());
    }

    /** Runs one cycle on demand, contending for the same lock the scheduler uses. */
    public SyncTaskRunner.Outcome syncNow(Long projectId) {
        Project project = require(projectId);
        if (cdcManager.strategyOf(project).isLogCdc()) {
            // 「立即同步」对 CDC 无意义:流一直在跑。正确的重放入口是「重建基线」(T17)。
            // 裸 i18n 键:ApiExceptionHandler 原样返回,浏览器端 JyncMessages 本地化。
            throw new IllegalStateException("error.project.cdc.use.rebaseline");
        }
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
            // Stop this node's CDC consumer before anything else: a live PG consumer
            // holds its replication slot (drop fails while the slot is active), and a
            // live cycle of any kind would re-create state rows right after the purge.
            cdcManager.ensureStopped(projectId);
            releasePgSlotIfAny(project);
            taskStore.deleteForProject(projectId);
            syncEngine.evictProjectCaches(projectId);
            lockService.evictProject(projectId);
            positionStore.delete(projectId);
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
     * P0-5: a PG logical replication slot is a persistent object ON THE SOURCE — deleting
     * the project without dropping it leaves the source retaining WAL for a consumer that
     * will never come back, until the disk fills. Best-effort on purpose: an unreachable
     * source must not block the local delete (dropSlotQuietly already swallows and WARNs).
     *
     * <p>This is an external side effect inside a @Transactional method: if the local
     * delete later rolls back, the slot stays gone. Accepted — the consumer's ensureSlot
     * re-creates it on the next start (at the cost of a re-baseline, not of correctness).
     */
    private void releasePgSlotIfAny(Project project) {
        if (cdcManager.strategyOf(project) != CaptureStrategy.PG_LOGICAL_CDC) {
            return;
        }
        Long sourceDbId = project.getSourceDbId();
        if (sourceDbId == null) {
            log.warn("PG-logical project {} has no source database id; cannot release its"
                    + " replication slot — drop it by hand on the source", project.getId());
            return;
        }
        DatabaseConfig source = databaseConfigRepository.findById(sourceDbId).orElse(null);
        if (source == null) {
            log.warn("Source database {} of deleted PG-logical project {} is gone; cannot"
                    + " release its replication slot — drop it by hand", sourceDbId,
                    project.getId());
            return;
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            PgLogicalCdcConsumer.dropSlotQuietly(conn, project.getId(), properties);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not reach the source of deleted PG-logical project {} to release"
                    + " its replication slot: {}", project.getId(), e.getMessage());
        }
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
                // Union of temporal/integral candidates plus SQL Server rowversion columns
                // (which the driver reports as Types.BINARY but behave as a bump-on-update
                // 8-byte counter — see CursorStrategy.isSqlServerRowversion).
                if (com.qqmu.jync.service.monitor.CursorStrategy.isCursorCandidate(column)) {
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
