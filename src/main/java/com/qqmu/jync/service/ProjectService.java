package com.qqmu.jync.service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.quartz.CronExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.CaptureStrategy;
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
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.PgLogicalCdcConsumer;
import com.qqmu.jync.service.cdc.YStreamServiceAdmin;
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

    /**
     * Whitespace can never appear inside a mapped target table name. UNICODE_CHARACTER_CLASS
     * makes {@code \s} match the same set the browser's {@code /\s/} does, including the
     * full-width space (U+3000) Chinese users type and the non-breaking space (U+00A0).
     */
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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
        // Read off the stored row before the merge below overwrites it: these describe the
        // OLD source, and the old source is where a PG logical slot may be left behind.
        boolean sourceChanged = false;
        Long previousSourceDbId = null;
        CaptureStrategy previousStrategy = null;
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
            if (sameContent(project, existing)) {
                // A save with nothing to change must not write: a merge would bump
                // updated_at (the form never carries it), append a PROJECT_SAVE audit row
                // that claims an edit nobody made, and re-create the Quartz trigger —
                // nudging the polling cadence. Report success by returning the stored row.
                return existing;
            }
            sourceChanged = !Objects.equals(project.getSourceDbId(), existing.getSourceDbId());
            previousSourceDbId = existing.getSourceDbId();
            previousStrategy = CaptureStrategyParser.parse(existing.getSyncConfig());
            endpointsChanged = sourceChanged
                    || !Objects.equals(project.getTargetDbId(), existing.getTargetDbId());
            if (endpointsChanged && isRunning(existing)) {
                // The running cycle (or live CDC consumer) holds the old endpoints; switching
                // them mid-flight would read one database and write another within a single
                // run. Stop the project first — unless it already is, in which case the
                // blocker is the wrapping-up round and telling the user to stop again is
                // advice they cannot act on.
                throw new IllegalStateException(stoppedButRunningKey(existing,
                        "error.project.edit.running"));
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
            // A PG logical slot lives ON the source, not in our metadata database: clearing
            // the position locally leaves it on the OLD server, where it keeps every WAL
            // segment after its restart point forever. Only a source change orphans it — a
            // target-only change keeps the source, and the slot is reused by the next
            // baseline (which is what keeps the re-baseline gap-free).
            if (sourceChanged && previousStrategy == CaptureStrategy.PG_LOGICAL_CDC) {
                releasePgSlot(saved.getId(), previousSourceDbId, "moved to another source");
            }
            // YStream 服务同为源库上的持久对象:新源会建新服务,旧服务没人再来停。
            if (sourceChanged && previousStrategy == CaptureStrategy.YSTREAM_CDC) {
                releaseYStream(saved.getId(), previousSourceDbId, "moved to another source");
            }
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
     * Sets or clears the explicit cursor column of one table and re-baselines that table.
     *
     * <p>A stored cursor belongs to the column that produced it: feeding its value to a
     * different column compares two unrelated value domains, so rows whose value in the new
     * column sits below the old watermark would never be delivered — most commonly when the
     * project was stopped for a while and the undelivered updates move to a different column.
     * Any change to the setting therefore clears that table's cursor and lets the next cycle
     * reload it, the same state {@link #resetProgress} produces for every table.
     *
     * <p>Refused while a cycle is running or still holds its lease: the in-flight cycle read
     * the old setting at its start, and a change mid-run would mix the two columns across
     * cycle boundaries.
     *
     * @param cursorColumn the column name, or blank to fall back to auto-detection
     * @throws IllegalArgumentException when the project does not exist
     * @throws IllegalStateException    when a cycle is running or its lease is still held
     */
    @Transactional
    public void saveCursorColumn(Long projectId, String tableName, String cursorColumn) {
        Project project = require(projectId);
        if (isRunning(project)) {
            throw new IllegalStateException(stoppedButRunningKey(project,
                    "error.project.stop.before.config"));
        }
        SyncConfig config = loadConfig(project);
        String value = cursorColumn == null || cursorColumn.isBlank() ? null : cursorColumn.trim();
        if (Objects.equals(config.cursorColumnFor(tableName), value)) {
            // Same setting: no write, and no needless full reload of the table.
            return;
        }
        // cursorColumnFor matches case-insensitively, so a differently-cased key must be
        // replaced rather than joined by a second entry, which would make the lookup ambiguous.
        config.getCursorColumns().keySet().removeIf(key -> key.equalsIgnoreCase(tableName));
        if (value != null) {
            config.getCursorColumns().put(tableName, value);
        }
        saveConfig(projectId, config);
        stateWriter.clearCursor(projectId, tableName);
    }

    /**
     * Creates a new project carrying the stored sync settings of {@code sourceProjectId}.
     *
     * <p>One save in one transaction: the row and its settings commit together, so a failure
     * cannot leave an empty project behind. The JSON is copied verbatim rather than
     * re-serialized from a parsed config, so fields this version does not know survive —
     * except {@code truncateBeforeInitialLoad}, which is forced off (see
     * {@link #cloneSettings(Project)}). Runtime state is not touched: a new project has none,
     * and the source's cursors describe a different target.
     */
    @Transactional
    public Project saveAsClone(Project clone, Long sourceProjectId) {
        Project source = require(sourceProjectId);
        clone.setSyncConfig(cloneSettings(source));
        log.info("Project '{}' cloned sync settings from project '{}'",
                clone.getName(), source.getName());
        return save(clone);
    }

    /**
     * The source's stored settings, with {@code truncateBeforeInitialLoad} forced off.
     *
     * <p>That flag empties the target on the clone's first run. Copied verbatim it turns
     * "duplicate this project" into a one-click wipe of a database the user may not have
     * meant to touch; the other destructive flags describe ongoing behavior and stay.
     *
     * <p>Read and written as a tree so fields this version does not know survive; the
     * string is returned untouched when there is nothing to reset.
     */
    private static String cloneSettings(Project source) {
        String json = source.getSyncConfig();
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = STRATEGY_JSON.readTree(json);
            if (node instanceof ObjectNode object
                    && object.path("truncateBeforeInitialLoad").asBoolean(false)) {
                object.put("truncateBeforeInitialLoad", false);
                return STRATEGY_JSON.writeValueAsString(object);
            }
            return json;
        } catch (JsonProcessingException e) {
            // A config this version cannot parse is copied verbatim — the behavior before
            // the clone feature existed. Refusing the clone would be worse: the user can
            // still repair the copied JSON afterwards.
            log.warn("Stored sync settings of project '{}' are not valid JSON; copying verbatim",
                    source.getName());
            return json;
        }
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
        // 表名映射落库前统一规范化:目标表名是数据库标识符,空格不可能属于它。表单边输入
        // 边去掉空格,但直连 POST 也必须被兜住;留空或改成与源表同名都表示"不映射"。
        config.setTableNameMapping(normalizeTableNameMapping(config.getTableNameMapping()));
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
        CaptureStrategy oldStrategy = CaptureStrategyParser.parse(project.getSyncConfig());
        // The endpoint is resolved for every explicit strategy submission, so the CDC gate
        // always checks against the real source type (strict == MYSQL: MariaDB and the
        // MySQL-wire compatibles speak a different binlog dialect). The gate itself lives in
        // CaptureStrategyParser so the bulk planner cannot accept what this path refuses.
        DatabaseConfig source = captureStrategy == null || captureStrategy.isBlank()
                || project.getSourceDbId() == null
                ? null
                : databaseConfigRepository.findById(project.getSourceDbId()).orElse(null);
        CaptureStrategy requested = CaptureStrategyParser.parseRequested(captureStrategy,
                source == null ? null : source.getType(),
                source == null ? null : source.resolvedFamily());
        if (requested != null) {
            config.setCaptureStrategy(requested);
        }
        // YStream's ADD_TABLES takes an explicit list; there is no "capture the whole schema"
        // mode to fall back on. A legacy empty selection (never went through the picker) reads
        // as "all tables", which the service cannot honour — and silently capturing nothing is
        // the one failure mode this whole feature exists to avoid.
        if (config.getCaptureStrategyOrDefault() == CaptureStrategy.YSTREAM_CDC
                && (!config.isObjectSelectionExplicit() || config.getTables().isEmpty())) {
            throw new IllegalArgumentException("error.project.cdc.tables.required");
        }
        // Table selection and name mapping are topology: changing them under a running
        // cycle makes it copy tables the old schedule never promised, and per-table cursors
        // are keyed by the OLD name. A strategy switch is exempt — that flow stops the
        // engine and clears its cursors in both directions by design.
        boolean switching = requested != null && requested != oldStrategy;
        SyncConfig previousConfig = parseConfigLenient(project.getSyncConfig());
        boolean tablesChanged = tableSelectionChanged(previousConfig, config);
        if (!switching && mappingChanged(previousConfig, config)
                && isRunning(project)) {
            throw new IllegalStateException(stoppedButRunningKey(project,
                    "error.project.mapping.running"));
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
                    () -> applyStrategySwitch(saved, from, to));
        } else {
            if (cron != null && !cron.isBlank() && !Boolean.TRUE.equals(saved.getEnabled())) {
                // The most common "cron does not work" report: the schedule was saved on a
                // stopped project. A saved schedule only runs while the project is started,
                // so say it plainly instead of leaving the user to wait for a fire.
                log.info("Project '{}': cron '{}' saved while the project is stopped;"
                                + " the schedule starts when the project is started",
                        saved.getName(), cron.trim());
            }
            // Interval or cron may have changed, so rebuild the trigger.
            afterCommit(saved.getId(), "reschedule", () -> scheduler.reschedule(saved));
            // In this branch the strategy never changed, so the stored one is still effective
            // (a form that omits captureStrategy keeps it — the pre-v1.5.0 behavior).
            if (oldStrategy == CaptureStrategy.YSTREAM_CDC && tablesChanged) {
                // A YStream service captures the table set it was built with. A table added
                // now has history before the current position that the stream will never
                // replay, and nothing reports the gap — so the position is dropped and the
                // next start re-baselines (with the new ADD_TABLES membership).
                afterCommit(saved.getId(), "ystream-tables-changed",
                        () -> resetYStreamForNewTables(saved));
            }
        }
        return saved;
    }

    /**
     * The runtime side of the C2 switch sequences, run after commit. Both directions
     * clear the polling cursors; everything else is directional:
     *
     * <ul>
     *   <li>→ a log strategy: join any consumer this node still holds FIRST — its shutdown
     *       re-persists the position, and a log→log switch would otherwise leave the old
     *       engine registered so the new one never starts. Then drop the Quartz job,
     *       invalidate the old position (the consumer re-baselines with a chunked full
     *       load), lift any polling-era ERROR hat that would silently block the start
     *       (Task 13: ensureStarted skips parked projects), and start the consumer — but
     *       only when the project is enabled. A disabled project (e.g. one just created by
     *       the bulk wizard) is reset only; its consumer starts from {@link #start(Long)},
     *       which re-baselines anyway because the position is gone.</li>
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
    private void applyStrategySwitch(Project project, CaptureStrategy from, CaptureStrategy to) {
        Long projectId = project.getId();
        if (to.isLogCdc()) {
            // BINLOG_CDC 与 LOGMINER_CDC 的切换序列完全一致:删 Quartz、清 cursor、
            // 删位点(下一个消费者无论哪种都从新锚点重跑基线)、摘 ERROR 帽。
            boolean enabled = Boolean.TRUE.equals(project.getEnabled());
            // Always join first, enabled or not: a consumer that is still leaving this node
            // re-persists the position in its shutdown path, so deleting before the join
            // leaves the freshly deleted row behind (stale anchor, no full reload). A
            // log→log switch adds a second reason: the old consumer would still be
            // registered, ensureStarted's idempotence guard would silently skip the new
            // engine, and the project would keep streaming with the previous strategy.
            // A no-op when this node holds no consumer for the project.
            cdcManager.ensureStopped(projectId);
            scheduler.unschedule(projectId);
            stateWriter.clearCursor(projectId);
            positionStore.delete(projectId);
            if (enabled) {
                taskStore.markCdcRunning(projectId);
                cdcManager.ensureStarted(project);
            } else {
                // 停用任务只做重置,不启动消费者。批量创建正是这条路:新任务一律
                // enabled=false,若在这里 ensureStarted,巡检(最多 10 秒后)会把它停掉,
                // 期间已可能往目标库写入,页面还会同时显示"已停止"和"CDC 运行中"。
                // 基线留给启用后的 start() 执行:无位点 → 全量。
                taskStore.markStoppedClearingResult(projectId);
                log.info("Project '{}': switched to {} while stopped; the consumer starts"
                        + " when the project is started", project.getName(), to);
            }
        } else {
            cdcManager.ensureStopped(projectId);
            stateWriter.clearCursor(projectId);
            scheduler.reschedule(project);
        }
        if (from == CaptureStrategy.YSTREAM_CDC && to != CaptureStrategy.YSTREAM_CDC) {
            // YStream 服务同样活在源库上:换走之后它既不会被沿用(新服务名带新 token),
            // 也不会被 grep 到,只会在实例的 32 个席位里一直占一个。
            releaseYStream(projectId, project.getSourceDbId(),
                    "switched away from YStream CDC");
        }
        if (from == CaptureStrategy.PG_LOGICAL_CDC && to != CaptureStrategy.PG_LOGICAL_CDC) {
            // Same leak as deleting the project (P0-5), but easier to miss: the row still
            // exists, so there is still something to look at on our side while the slot
            // pins WAL on the source. Runs after the stop above — PG refuses to drop a slot
            // that is still active (55006).
            releasePgSlot(projectId, project.getSourceDbId(),
                    "switched away from PG logical CDC");
        }
    }

    /**
     * Drops the YStream position so the changed table selection gets a full baseline.
     *
     * <p>Runs after commit, like {@link #applyStrategySwitch}: the join must complete before
     * the delete (a consumer still leaving re-persists its position on shutdown), and a
     * rolled-back save must not have touched the running consumer at all.
     */
    private void resetYStreamForNewTables(Project project) {
        Long projectId = project.getId();
        cdcManager.ensureStopped(projectId);
        positionStore.delete(projectId);
        if (Boolean.TRUE.equals(project.getEnabled())) {
            taskStore.markCdcRunning(projectId);
            cdcManager.ensureStarted(project);
            log.info("Project '{}': table selection changed; YStream re-baselines from the"
                    + " new table set", project.getName());
        } else {
            // Disabled: nothing to start. The missing position is what makes the next
            // start() re-baseline, so only the panel state is reset here.
            taskStore.markStoppedClearingResult(projectId);
            log.info("Project '{}': table selection changed while stopped; the next start"
                    + " re-baselines the YStream capture", project.getName());
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

    /**
     * The refusal message for a guard that {@link #isRunning} tripped.
     *
     * <p>When the project is enabled, "stop the sync first" is the actionable advice. When it
     * is not, the only thing still running is a round that is wrapping up (here or on another
     * instance) — repeating "stop the sync" to somebody who already did leaves them with no
     * next step, so say what actually ends it: wait, or force-unlock a wedged holder.
     */
    private static String stoppedButRunningKey(Project project, String enabledKey) {
        return Boolean.TRUE.equals(project.getEnabled())
                ? enabledKey : "error.project.stillStopping";
    }

    /**
     * The form carries the same values the row already holds. The source/target comparison
     * is null-safe; a blank textarea arrives as "" while older rows may store null, so the
     * description is compared with null folded to "".
     */
    private static boolean sameContent(Project form, Project stored) {
        return form.getName().equals(stored.getName())
                && normalizeBlank(form.getDescription()).equals(normalizeBlank(stored.getDescription()))
                && Objects.equals(form.getSourceDbId(), stored.getSourceDbId())
                && Objects.equals(form.getTargetDbId(), stored.getTargetDbId());
    }

    private static String normalizeBlank(String value) {
        return value == null ? "" : value;
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

    /**
     * Removes whitespace from mapped target names and drops entries that mean no rename.
     *
     * <p>A target table name is an identifier, so whitespace can never be part of it: it is
     * stripped rather than rejected, because the form strips it while typing too. A blank
     * value, or one identical to its source table, says "keep the source name" and is not
     * stored — the persisted map then contains only real renames. Normalising BEFORE the
     * duplicate-target check also means {@code " orders "} and {@code "orders"} are seen as
     * the same target and refused instead of slipping through as distinct spellings.
     */
    private static Map<String, String> normalizeTableNameMapping(Map<String, String> raw) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            String source = entry.getKey().trim();
            String target = WHITESPACE.matcher(entry.getValue()).replaceAll("");
            if (target.isEmpty() || target.equals(source)) {
                continue;
            }
            normalized.put(source, target);
        }
        return normalized;
    }

    /**
     * The set of captured table names differs from what the row last persisted.
     *
     * <p>Name mappings are deliberately ignored: renaming a target table does not change
     * what the source captures, so it must not cost a re-baseline. The legacy "empty means
     * all" rule is shared with {@link #mappingChanged}.
     */
    private static boolean tableSelectionChanged(SyncConfig previous, SyncConfig next) {
        Set<String> oldTables = previous == null || previous.getTables() == null
                ? Set.of() : previous.getTables();
        Set<String> newTables = next.getTables() == null ? Set.of() : next.getTables();
        if (previous != null && !previous.isObjectSelectionExplicit() && oldTables.isEmpty()) {
            return false;
        }
        return !oldTables.equals(newTables);
    }

    /** Table selection or name mapping differs from what the row last persisted. */
    private static boolean mappingChanged(SyncConfig previous, SyncConfig next) {
        // Compare invariants: the persisted map is always normalised, so a legacy entry
        // such as " A " -> " B " must not look like a topology change when the form
        // resubmits it (the stored value would otherwise differ from what is saved next).
        if (previous != null) {
            previous.setTableNameMapping(
                    normalizeTableNameMapping(previous.getTableNameMapping()));
        }
        Set<String> oldTables = previous == null || previous.getTables() == null
                ? Set.of() : previous.getTables();
        Set<String> newTables = next.getTables() == null ? Set.of() : next.getTables();
        Map<String, String> oldNames = previous == null || previous.getTableNameMapping() == null
                ? Map.of() : previous.getTableNameMapping();
        Map<String, String> newNames = next.getTableNameMapping() == null
                ? Map.of() : next.getTableNameMapping();
        if (!oldNames.equals(newNames)) {
            return true;
        }
        // 没有 explicit 标记的空集合语义是「全部」(见 SyncConfig 顶部)。这种行来自
        // 「对象选择」出现之前保存的配置,或首次保存时源库列举失败、只有策略落了库。
        // 表单会把列出的每张表都勾上再提交,于是第一次保存会把「全部」物化成一张清单:
        // 生效的选择并没有变(「全部」只能被收窄,不可能多出旧轮次没承诺的表,而收窄
        // 对已在收尾的轮次无害 —— 它启动时已取好自己的表清单)。把它算作拓扑变更,
        // 就会让「只改同步选项」的保存被「上一轮仍在收尾」拒掉,用户看到开关原样退回。
        if (previous != null && !previous.isObjectSelectionExplicit() && oldTables.isEmpty()) {
            return false;
        }
        return !oldTables.equals(newTables);
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
        // Nothing to sync is refused up front: starting would only schedule connect-and-read
        // cycles (or launch a consumer mining for events it discards). The UI disables the
        // button; this is the server-side backstop for a stale page or a direct API call.
        // Bare keys: ApiExceptionHandler returns them as-is and the browser localizes them.
        SyncConfig config = loadConfig(project);
        // The save path validates the expression, but a config can also arrive by clone, by
        // a direct database edit, or from an older version that stored it unchecked.
        // Scheduling runs after commit: an invalid cron there leaves the project enabled
        // with no job — exactly the "my cron never fires" symptom — so refuse the start.
        String cron = config.getCronExpression();
        // A CDC project never gets a Quartz trigger, so a leftover cron must not block it.
        if (!config.getCaptureStrategyOrDefault().isLogCdc()
                && cron != null && !cron.isBlank()
                && !CronExpression.isValidExpression(cron.trim())) {
            throw new IllegalStateException("error.project.cron.invalid");
        }
        if (config.hasNoSyncObjects()) {
            logEmptyWorkRefusal("start", project, config, "error.project.no.objects");
            throw new IllegalStateException("error.project.no.objects");
        }
        // The option switches only reduce a POLLING/FULL_COMPARE cycle to a no-op; a CDC
        // consumer replays row events regardless of them, so its start must stay allowed.
        if (!config.getCaptureStrategyOrDefault().isLogCdc() && config.hasNoPollingWork()) {
            logEmptyWorkRefusal("start", project, config, "error.project.no.options");
            throw new IllegalStateException("error.project.no.options");
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

    /**
     * 一条「没活可干」的拒绝只带走一个 i18n 键,判据本身没有落点。同一条
     * {@code error.project.no.options} 可能来自「五个开关全关」,也可能来自「已开启的
     * 选项在同步对象里一个都没选」——事后的截图只反映页面,复现不了当时的保存态。
     * 把判据写进 INFO 日志(策略、五个开关、显式选择标记、三类已选数量),下一次反馈
     * 就有可对照的记录。不含任何连接信息,可以安全留在生产日志里。
     */
    private void logEmptyWorkRefusal(String action, Project project, SyncConfig config,
                                     String key) {
        log.info("Refusing {} for project '{}' ({}): strategy={}, structure={}, data={},"
                        + " indexes={}, views={}, procedures={}, explicitSelection={},"
                        + " selectedTables={}, selectedViews={}, selectedProcedures={}",
                action, project.getName(), key, config.getCaptureStrategyOrDefault(),
                config.isSyncStructure(), config.isSyncData(), config.isSyncIndexes(),
                config.isSyncViews(), config.isSyncProcedures(),
                config.isObjectSelectionExplicit(),
                config.getTables().size(), config.getViews().size(),
                config.getProcedures().size());
    }

    /**
     * Disables the project, removes its schedule, and asks an in-flight polling cycle to
     * abort at its next committed batch.
     *
     * <p>The stop request is what makes the button mean what it says: without it a running
     * full load keeps renewing its lease for minutes, and every config save is refused with
     * "stop the sync first" — which the user has just done. The cycle unwinds on its own at
     * a safe point, so nothing is cut mid-write and the lease is released by its owner.
     */
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
            // Ask the cycle before dropping the schedule: the schedule entry is already
            // gone for a running fire, and only the request reaches the thread that holds
            // the lease.
            taskRunner.requestStop(projectId);
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
        SyncConfig config = loadConfig(project);
        if (config.hasNoSyncObjects()) {
            logEmptyWorkRefusal("sync-now", project, config, "error.project.no.objects");
            throw new IllegalStateException("error.project.no.objects");
        }
        // CDC already returned above, so this is always a polling strategy.
        if (config.hasNoPollingWork()) {
            logEmptyWorkRefusal("sync-now", project, config, "error.project.no.options");
            throw new IllegalStateException("error.project.no.options");
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
        // Progress rows play no part in CDC: the baseline wipes them itself before its full
        // load (CdcBaselineService → SyncEngine.resetProject), and streaming afterwards
        // follows the position row only. Clearing them would report success and change
        // nothing — "the next run reloads everything" would be a false promise. The button
        // that means it is 「重建基线」, next to this one.
        if (cdcManager.strategyOf(project).isLogCdc()) {
            throw new IllegalStateException("error.project.cdc.reset.use.rebaseline");
        }
        if (Boolean.TRUE.equals(project.getEnabled())) {
            throw new IllegalStateException("error.project.stop.before.reset");
        }
        // stop() only clears the flag: a cycle already in flight keeps its lease until it
        // finishes, and a peer node's cycle holds one the flag cannot describe at all.
        // Resetting underneath it races the very rows this reset deletes — the cycle writes
        // its cursors back after the purge, so the reset reports success while the next run
        // still resumes from the old watermark. An expired lease does not block: the holder
        // is gone and TTL reclaim / force-unlock is the documented remedy.
        if (isRunning(project)) {
            throw refusalWhileRunning("reset", project);
        }
        syncEngine.resetProject(projectId);
    }

    /**
     * The refusal a destructive action throws while a sync lease is live, naming the holder
     * in the log.
     *
     * <p>The user only sees "a cycle is still running", because one sentence has to cover both
     * a round on this node and a peer instance's lease — the page cannot tell them apart. The
     * log is where the difference lives: when someone reports «清空进度/删除被拒，但我已经停了»,
     * the owner and expiry are the first thing needed to say whether the lease is alive, a
     * peer's, or a dead owner waiting out its TTL (the force-unlock case).
     */
    private IllegalStateException refusalWhileRunning(String action, Project project) {
        SyncTask task = taskStore.find(project.getId()).orElse(null);
        log.warn("Refusing to {} project {} ('{}'): sync lease held by {} until {}", action,
                project.getId(), project.getName(),
                task == null ? "an unknown owner" : task.getLockOwner(),
                task == null || task.getLockExpiresAt() == null ? "unknown"
                        : task.getLockExpiresAt());
        return new IllegalStateException("error.project.sync.in.progress");
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
                throw refusalWhileRunning("delete", project);
            }
            // Stop this node's CDC consumer before anything else: a live PG consumer
            // holds its replication slot (drop fails while the slot is active), and a
            // live cycle of any kind would re-create state rows right after the purge.
            cdcManager.ensureStopped(projectId);
            releasePgSlotIfAny(project);
            releaseYStreamIfAny(project);
            // The sync_task row carries the lease acquired above and is deleted by this
            // transaction. Telling the handle now keeps its close() from issuing a release
            // UPDATE against the row this transaction is deleting: a second connection would
            // block on that row's lock for H2's whole timeout, adding seconds to a delete and
            // logging a release failure for a row that is going away anyway.
            lock.leaseRowDeletedByCaller();
            taskStore.deleteForProject(projectId);
            syncEngine.evictProjectCaches(projectId);
            lockService.evictProject(projectId);
            positionStore.delete(projectId);
            progressRepository.deleteByProjectId(projectId);
            snapshotService.deleteAllForProject(projectId);
            changeLogRepository.deleteByProjectId(projectId);
            projectRepository.deleteById(projectId);
            // Drop the runner's per-project state (queued rerun, attempt count, stop epoch,
            // heartbeat marker) so a reused id cannot inherit a ghost cycle.
            taskRunner.forget(projectId);
            auditService.record(AuditAction.PROJECT_DELETE,
                    "Deleted project '" + project.getName() + "' and all its sync state");
        }
        log.info("Deleted project '{}' and all its sync state", project.getName());
    }

    /**
     * P0-5: a PG logical replication slot is a persistent object ON THE SOURCE — deleting
     * the project without dropping it leaves the source retaining WAL for a consumer that
     * will never come back, until the disk fills.
     */
    private void releasePgSlotIfAny(Project project) {
        if (cdcManager.strategyOf(project) != CaptureStrategy.PG_LOGICAL_CDC) {
            return;
        }
        releasePgSlot(project.getId(), project.getSourceDbId(), "deleted");
    }

    /** The YStream counterpart of {@link #releasePgSlotIfAny}: services live on the source. */
    private void releaseYStreamIfAny(Project project) {
        if (cdcManager.strategyOf(project) != CaptureStrategy.YSTREAM_CDC) {
            return;
        }
        releaseYStream(project.getId(), project.getSourceDbId(), "deleted");
    }

    /**
     * Best-effort drop of every YStream service the project owns on {@code sourceDbId}.
     *
     * <p>Same leak, same three callers as {@link #releasePgSlot}: a service behind a deleted
     * project, a strategy switched away from YStream, or a project moved to another source
     * keeps one of the instance's 32 seats and its capture scope forever, on a database the
     * running product no longer touches.
     *
     * <p>Deliberately never throws: an unreachable source must not block the local change
     * ({@code releaseQuietly} already swallows and WARNs). A rollback after the release
     * leaves the service gone; the lifecycle recreates it on the next start at the cost of
     * a re-baseline, not of correctness.
     */
    private void releaseYStream(Long projectId, Long sourceDbId, String because) {
        if (sourceDbId == null) {
            log.warn("YStream project {} has no source database id ({}); cannot release its"
                    + " services — drop them by hand on the source", projectId, because);
            return;
        }
        DatabaseConfig source = databaseConfigRepository.findById(sourceDbId).orElse(null);
        if (source == null) {
            log.warn("Source database {} of YStream project {} is gone ({}); cannot release"
                    + " its services — drop them by hand", sourceDbId, projectId, because);
            return;
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            YStreamServiceAdmin.releaseQuietly(conn, projectId);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not reach the source of YStream project {} ({}) to release its"
                    + " services: {}", projectId, because, e.getMessage());
        }
    }

    /**
     * Best-effort drop of the project's PG logical slot on {@code sourceDbId}.
     *
     * <p>Every caller leaves the slot with no consumer for good: the project is deleted, its
     * capture strategy moved away from PG logical decoding, or its source moved to another
     * server. In all three the slot would pin every WAL segment after its restart point
     * forever, on a database the running product no longer touches — nobody would think to
     * look for it again.
     *
     * <p>Deliberately never throws: an unreachable source must not block the local change
     * ({@code dropSlotQuietly} already swallows and WARNs). Called inside a
     * {@code @Transactional} method, so on a later rollback the slot stays gone — accepted,
     * because the consumer's {@code ensureSlot} re-creates it on the next start at the cost
     * of a re-baseline, not of correctness.
     *
     * @param because short participle for the log line ({@code deleted},
     *                {@code switched away from PG logical CDC}, {@code moved to another source})
     */
    private void releasePgSlot(Long projectId, Long sourceDbId, String because) {
        if (sourceDbId == null) {
            log.warn("PG-logical project {} has no source database id ({}); cannot release"
                    + " its replication slot — drop it by hand on the source", projectId, because);
            return;
        }
        DatabaseConfig source = databaseConfigRepository.findById(sourceDbId).orElse(null);
        if (source == null) {
            log.warn("Source database {} of PG-logical project {} is gone ({}); cannot release"
                    + " its replication slot — drop it by hand", sourceDbId, projectId, because);
            return;
        }
        try (Connection conn = dataSourceManager.getConnection(source)) {
            PgLogicalCdcConsumer.dropSlotQuietly(conn, projectId, properties);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not reach the source of PG-logical project {} ({}) to release its"
                    + " replication slot: {}", projectId, because, e.getMessage());
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

        // The declared family, not the bare type: a CUSTOM connection carries GENERIC and
        // would otherwise be read through the JDBC-only generic reader.
        MetadataReader reader =
                readerFactory.forType(source.getType(), source.resolvedFamily());
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

    /** One FULL_COMPARE table worth warning about, and why. */
    public record FullCompareLoad(TableMeta table, boolean overCap, long recommendedIntervalMs) {

        public TableMeta getTable() {
            return table;
        }

        public boolean isOverCap() {
            return overCap;
        }

        public long getRecommendedIntervalMs() {
            return recommendedIntervalMs;
        }
    }

    /** The FULL_COMPARE load warnings for one task, with the interval the estimate used. */
    public record FullCompareLoadReport(long intervalMs, long maxRows,
                                        List<FullCompareLoad> tables) {

        public long getIntervalMs() {
            return intervalMs;
        }

        public long getMaxRows() {
            return maxRows;
        }

        public List<FullCompareLoad> getTables() {
            return tables;
        }

        public boolean isEmpty() {
            return tables.isEmpty();
        }

        public boolean isOverCap() {
            return tables.stream().anyMatch(FullCompareLoad::overCap);
        }
    }

    /**
     * Selected tables whose FULL_COMPARE cycle is worth warning about, largest first.
     *
     * <p>Two conditions qualify. A table above {@code full-compare-max-rows} is over the cap
     * that only gates the <em>automatic</em> strategy choice — an explicit project-level
     * FULL_COMPARE bypasses it. A table within the cap can still be too heavy for the task's
     * current poll interval, because every cycle re-reads and re-upserts the whole table;
     * {@link com.qqmu.jync.service.sync.FullCompareLoadEstimator} supplies that estimate.
     *
     * <p>Keyless tables are excluded: recurring FULL_COMPARE on them is skipped after the
     * one-time seed, so they are not read every cycle. A null approximate count also stays
     * out — the warning must not guess.
     */
    public FullCompareLoadReport inspectFullCompareLoad(Long projectId, SyncConfig config) {
        long intervalMs = com.qqmu.jync.service.sync.FullCompareLoadEstimator
                .effectiveIntervalMs(config, properties.getPollInterval());
        long maxRows = properties.getFullCompareMaxRows();
        if (config == null
                || config.getCaptureStrategyOrDefault() != CaptureStrategy.FULL_COMPARE) {
            return new FullCompareLoadReport(intervalMs, maxRows, List.of());
        }
        List<FullCompareLoad> loads = snapshotService
                .loadAll(projectId, ObjectType.TABLE, TableMeta.class)
                .values().stream()
                .filter(table -> table.getName() != null
                        && config.includesTable(table.getName()))
                .filter(TableMeta::hasPrimaryKey)
                .filter(table -> table.getApproximateRowCount() != null)
                .map(table -> {
                    long rows = table.getApproximateRowCount();
                    return new FullCompareLoad(table, rows > maxRows,
                            com.qqmu.jync.service.sync.FullCompareLoadEstimator
                                    .recommendedIntervalMs(rows));
                })
                .filter(load -> load.overCap()
                        || com.qqmu.jync.service.sync.FullCompareLoadEstimator.isHeavy(
                                load.table().getApproximateRowCount(), intervalMs))
                .sorted(Comparator.comparingLong(
                                (FullCompareLoad load) -> load.table().getApproximateRowCount())
                        .reversed()
                        .thenComparing(load -> load.table().getName()))
                .collect(Collectors.toList());
        return new FullCompareLoadReport(intervalMs, maxRows, loads);
    }

    /**
     * Columns of one source table, so the user can pick a cursor column explicitly.
     *
     * <p>Only comparable types are offered: a cursor must support {@code >} and {@code MAX()}.
     */
    public List<String> listCursorCandidates(Long projectId, String tableName) {
        TableMeta table = readSourceTable(projectId, tableName);
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
    }

    /**
     * Every column of one source table, in metadata order — the picker behind features that
     * key on a column name (column cleansing) should offer the real list instead of trusting
     * free typing. No type filtering: the UI shows the table as it is, and a cleansing rule
     * simply has no effect on a column that is not a string.
     */
    public List<String> listTableColumns(Long projectId, String tableName) {
        List<String> columns = new ArrayList<>();
        for (var column : readSourceTable(projectId, tableName).getColumns()) {
            columns.add(column.getName());
        }
        return columns;
    }

    /** Resolves the project's source connection and reads one table's metadata. */
    private TableMeta readSourceTable(Long projectId, String tableName) {
        Project project = require(projectId);
        if (project.getSourceDbId() == null) {
            // Endpoints are optional until start(), so findById(null) would otherwise leak
            // its internal English error to the caller.
            throw new IllegalStateException("error.project.source.required");
        }
        DatabaseConfig source = databaseConfigRepository.findById(project.getSourceDbId())
                .orElseThrow(() -> new IllegalStateException("error.connection.missing"));
        MetadataReader reader =
                readerFactory.forType(source.getType(), source.resolvedFamily());
        try (Connection conn = dataSourceManager.getConnection(source)) {
            String schema = source.getSchemaName() != null && !source.getSchemaName().isBlank()
                    ? source.getSchemaName() : reader.resolveDefaultSchema(conn);
            return reader.readTable(conn, schema, tableName);
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
                // A negative chunk index is the keyless walk's pre-first-page marker: it guards
                // the crash window before the first checkpoint row and commits no rows, so it
                // is not a chunk and must not advance the panel.
                if (c.getChunkIndex() != null && c.getChunkIndex() < 0) {
                    continue;
                }
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

    /** When the polling job next runs (server zone), or null when it is not scheduled. */
    public java.time.LocalDateTime nextFireTime(Long projectId) {
        return scheduler.nextFireTime(projectId);
    }

    /**
     * A polling cycle executing on this node, presented for the project page.
     *
     * <p>{@code minutesSinceProgress} is the age of the last explicit lease renewal — the
     * last time the cycle reached a safe point. It is not the age of the lease itself: the
     * watchdog keeps renewing a wedged cycle's lease for up to ten TTLs, which is exactly
     * the window in which the page must stop saying "almost done".
     */
    public record CycleView(boolean running, java.time.LocalDateTime startedAt,
                            java.time.LocalDateTime lastProgressAt, long minutesSinceProgress,
                            boolean stuck, long stuckThresholdMinutes) {
    }

    /**
     * The polling cycle running for this project on this node, or null when none is.
     *
     * <p>Null rather than empty: the page renders it directly, and "no cycle" is the normal
     * state of a stopped or idle project.
     */
    public CycleView activeCycle(Long projectId) {
        SyncLockService.ActiveCycle cycle = lockService.activePollingCycle(projectId).orElse(null);
        return cycle == null ? null : cycleView(cycle, properties.getStuckThresholdMs(),
                Instant.now());
    }

    /** Pure mapping, kept static so the threshold arithmetic is testable without the service. */
    static CycleView cycleView(SyncLockService.ActiveCycle cycle, long stuckThresholdMs,
                               Instant now) {
        long idleMs = Math.max(0L, java.time.Duration.between(cycle.lastProgressAt(), now)
                .toMillis());
        boolean detectionEnabled = stuckThresholdMs > 0;
        return new CycleView(true,
                java.time.LocalDateTime.ofInstant(cycle.startedAt(), java.time.ZoneId.systemDefault()),
                java.time.LocalDateTime.ofInstant(cycle.lastProgressAt(),
                        java.time.ZoneId.systemDefault()),
                idleMs / 60_000L,
                detectionEnabled && idleMs >= stuckThresholdMs,
                Math.max(0L, stuckThresholdMs / 60_000L));
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
