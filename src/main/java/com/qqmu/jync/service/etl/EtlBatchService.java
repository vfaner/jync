package com.qqmu.jync.service.etl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.ConnectionCatalogService;

/**
 * CRUD and execution for ETL batches.
 *
 * <p>A batch owns the connection pair and the cron; its member tasks keep the per-table
 * extraction and load settings. Batch execution creates one {@link EtlConnections} and runs the
 * members sequentially, so a whole database costs one pair of connection pools per run.
 */
@Service
public class EtlBatchService {

    private static final Logger log = LoggerFactory.getLogger(EtlBatchService.class);

    private final EtlBatchRepository batchRepository;
    private final EtlTaskRepository taskRepository;
    private final DatabaseConfigService databaseConfigService;
    private final EtlTaskService taskService;
    private final EtlCheckpointStore checkpointStore;
    private final DataSourceManager dataSourceManager;
    private final EtlExecutionGuard guard;
    private final ConnectionCatalogService catalogService;
    private final SqlDialectFactory dialectFactory;
    private final Executor executor;
    private final ApplicationEventPublisher events;

    public EtlBatchService(EtlBatchRepository batchRepository,
                           EtlTaskRepository taskRepository,
                           DatabaseConfigService databaseConfigService,
                           EtlTaskService taskService,
                           EtlCheckpointStore checkpointStore,
                           DataSourceManager dataSourceManager,
                           EtlExecutionGuard guard,
                           ConnectionCatalogService catalogService,
                           SqlDialectFactory dialectFactory,
                           @Qualifier("etlExecutor") Executor executor,
                           ApplicationEventPublisher events) {
        this.batchRepository = batchRepository;
        this.taskRepository = taskRepository;
        this.databaseConfigService = databaseConfigService;
        this.taskService = taskService;
        this.checkpointStore = checkpointStore;
        this.dataSourceManager = dataSourceManager;
        this.guard = guard;
        this.catalogService = catalogService;
        this.dialectFactory = dialectFactory;
        this.executor = executor;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<EtlBatch> findAll() {
        return batchRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Optional<EtlBatch> findById(Long id) {
        return batchRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<EtlTask> members(Long batchId) {
        return taskRepository.findByBatchIdOrderByIdAsc(batchId);
    }

    /**
     * Creates a batch and one member task per selected table. The catalog is re-read here so a
     * stale form cannot create members for tables that no longer exist; the whole selection is
     * refused rather than half-created.
     */
    @Transactional
    public int createWithTables(EtlBatch form, List<String> tables) {
        validateSettings(form);
        List<String> chosen = requireSelection(tables);
        DatabaseConfig source = EtlTaskValidation.validateConnections(databaseConfigService,
                form.getSourceDbId(), form.getTargetDbId());
        DatabaseConfig target = targetConfig(form.getTargetDbId());
        ConnectionCatalogService.Tables catalog = catalogService.listTables(form.getSourceDbId());
        List<String> ordered = validateTables(chosen, catalog, Set.of(), target);

        EtlBatch batch = new EtlBatch();
        batch.setName(form.getName().trim());
        batch.setDescription(trimToNull(form.getDescription()));
        batch.setCronExpression(trimToNull(form.getCronExpression()));
        batch.setTimeoutSeconds(form.getTimeoutSeconds());
        batch.setEnabled(false);
        batch.setSourceDbId(form.getSourceDbId());
        batch.setTargetDbId(form.getTargetDbId());
        batch = batchRepository.save(batch);

        List<EtlTask> created = buildMembers(batch, ordered, catalog.schema(), source);
        taskRepository.saveAll(created);
        events.publishEvent(new EtlBatchChangedEvent(batch.getId(), false));
        return created.size();
    }

    /** Adds more tables to an existing batch, skipping nothing: a duplicate target is refused. */
    @Transactional
    public int addTables(Long batchId, List<String> tables) {
        EtlBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
        List<String> chosen = requireSelection(tables);
        DatabaseConfig source = EtlTaskValidation.validateConnections(databaseConfigService,
                batch.getSourceDbId(), batch.getTargetDbId());
        DatabaseConfig target = targetConfig(batch.getTargetDbId());
        ConnectionCatalogService.Tables catalog = catalogService.listTables(batch.getSourceDbId());

        Set<String> taken = new LinkedHashSet<>();
        for (EtlTask member : taskRepository.findByBatchIdOrderByIdAsc(batchId)) {
            if (member.getTargetTable() != null) {
                taken.add(EtlSqlSupport.targetKey(target, member.getTargetTable()));
            }
        }
        List<String> ordered = validateTables(chosen, catalog, taken, target);

        List<EtlTask> created = buildMembers(batch, ordered, catalog.schema(), source);
        taskRepository.saveAll(created);
        events.publishEvent(new EtlBatchChangedEvent(batchId, false));
        return created.size();
    }

    @Transactional
    public void removeMember(Long batchId, Long taskId) {
        EtlBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
        EtlTask member = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        if (!batchId.equals(member.getBatchId())) {
            throw new IllegalArgumentException("error.etl.not.found");
        }
        if (isRunning(batchId)) {
            // The batch run took its member list when it started; removing one now leaves the
            // runner executing a deleted row and clearing a checkpoint that is still being written.
            throw new IllegalStateException("error.etl.batch.running");
        }
        if (guard.isRunning(taskId)) {
            throw new IllegalStateException("error.etl.batch.memberRunning");
        }
        // The task row and its resume position go together; a checkpoint without its source is noise.
        checkpointStore.clear(taskId);
        taskRepository.delete(member);
        events.publishEvent(new EtlTaskChangedEvent(taskId, true));

        if (taskRepository.findByBatchIdOrderByIdAsc(batchId).isEmpty() && Boolean.TRUE.equals(batch.getEnabled())) {
            // Nothing left to run: leaving the schedule enabled would fire an empty batch forever.
            batch.setEnabled(false);
            batchRepository.save(batch);
            events.publishEvent(new EtlBatchChangedEvent(batchId, false));
        }
    }

    /**
     * Queues one batch run and returns immediately. Members execute sequentially on the etl
     * executor, so a batch of tables occupies one slot rather than one slot per table.
     */
    public boolean submit(Long batchId) {
        if (!batchRepository.existsById(batchId)) {
            // A stale tab pressing Run: "no tables yet" would send the operator looking in the
            // wrong place.
            throw new IllegalArgumentException("error.etl.batch.not.found");
        }
        if (!guard.tryAcquire(batchKey(batchId))) {
            return false;
        }
        try {
            if (taskRepository.findByBatchIdOrderByIdAsc(batchId).isEmpty()) {
                throw new IllegalStateException("error.etl.batch.empty");
            }
            executor.execute(() -> {
                try {
                    execute(batchId);
                } catch (RuntimeException e) {
                    log.warn("ETL batch {} crashed outside the member loop: {}", batchId, e.getMessage());
                    // Otherwise the page keeps showing the previous run as if nothing ran.
                    recordCrash(batchId, "crashed outside the member loop: " + e);
                } finally {
                    guard.release(batchKey(batchId));
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // Releasing matters more than the message: a held key would read as running forever.
            guard.release(batchKey(batchId));
            throw new IllegalStateException("error.etl.queue.full");
        } catch (RuntimeException e) {
            guard.release(batchKey(batchId));
            throw e;
        }
    }

    /**
     * Runs the members in id order on one connection pair. A busy member is skipped, and a
     * failure never stops the tables behind it; the summary records both.
     */
    public void execute(Long batchId) {
        EtlBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
        List<EtlTask> members = taskRepository.findByBatchIdOrderByIdAsc(batchId);

        int ok = 0;
        int failed = 0;
        int skipped = 0;
        long rowsRead = 0;
        long rowsWritten = 0;
        try (EtlConnections connections = new EtlConnections(dataSourceManager)) {
            for (EtlTask member : members) {
                Long memberId = member.getId();
                if (!guard.tryAcquire(memberId)) {
                    skipped++;
                    log.info("ETL batch {} skipped task {}: it is already running", batchId, memberId);
                    continue;
                }
                try {
                    EtlRunResult result = taskService.executeWithin(memberId, connections);
                    rowsRead += result.rowsRead();
                    rowsWritten += result.rowsWritten();
                    if (result.success()) {
                        ok++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    taskService.recordFailure(memberId, e.getMessage());
                    log.warn("ETL task {} failed inside batch {}: {}", memberId, batchId, e.getMessage());
                } finally {
                    guard.release(memberId);
                }
            }
        }

        recordRun(batch, members.size(), ok, failed, skipped, rowsRead, rowsWritten);
    }

    @Transactional
    public EtlBatch saveSettings(EtlBatch form) {
        validateSettings(form);
        EtlBatch entity;
        if (form.getId() == null) {
            EtlTaskValidation.validateConnections(databaseConfigService,
                    form.getSourceDbId(), form.getTargetDbId());
            entity = new EtlBatch();
            entity.setEnabled(false);
            entity.setSourceDbId(form.getSourceDbId());
            entity.setTargetDbId(form.getTargetDbId());
        } else {
            entity = batchRepository.findById(form.getId())
                    .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
            // The connection pair is fixed once members exist; the form's values are ignored.
        }
        if (Boolean.TRUE.equals(entity.getEnabled())
                && (form.getCronExpression() == null || form.getCronExpression().isBlank())) {
            // An enabled batch without a cron is cancelled by the scheduler but still shown as
            // enabled, and nothing re-registers it. setEnabled already refuses this state.
            throw new IllegalArgumentException("error.etl.cron.required");
        }
        entity.setName(form.getName().trim());
        entity.setDescription(trimToNull(form.getDescription()));
        entity.setCronExpression(trimToNull(form.getCronExpression()));
        entity.setTimeoutSeconds(form.getTimeoutSeconds());

        EtlBatch saved = batchRepository.save(entity);
        events.publishEvent(new EtlBatchChangedEvent(saved.getId(), false));
        return saved;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        EtlBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
        if (enabled) {
            if (batch.getCronExpression() == null || batch.getCronExpression().isBlank()) {
                throw new IllegalArgumentException("error.etl.cron.required");
            }
            if (taskRepository.findByBatchIdOrderByIdAsc(id).isEmpty()) {
                throw new IllegalArgumentException("error.etl.batch.empty");
            }
        }
        batch.setEnabled(enabled);
        batchRepository.save(batch);
        events.publishEvent(new EtlBatchChangedEvent(id, false));
    }

    @Transactional
    public void delete(Long id) {
        EtlBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.batch.not.found"));
        if (isRunning(id)) {
            throw new IllegalStateException("error.etl.batch.running");
        }
        List<EtlTask> members = taskRepository.findByBatchIdOrderByIdAsc(id);
        for (EtlTask member : members) {
            if (guard.isRunning(member.getId())) {
                throw new IllegalStateException("error.etl.batch.memberRunning");
            }
        }
        for (EtlTask member : members) {
            // The task rows go with the batch; a resume position without its source is noise.
            checkpointStore.clear(member.getId());
        }
        taskRepository.deleteAll(members);
        batchRepository.delete(batch);
        events.publishEvent(new EtlBatchChangedEvent(id, true));
    }

    public boolean isRunning(Long batchId) {
        return guard.isRunning(batchKey(batchId));
    }

    static String batchKey(Long batchId) {
        return "batch:" + batchId;
    }

    private static void validateSettings(EtlBatch form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("error.etl.batch.name.required");
        }
        if (form.getName().trim().length() > 128) {
            throw new IllegalArgumentException("error.etl.batch.name.tooLong");
        }
        if (form.getCronExpression() != null && !form.getCronExpression().isBlank()
                && !CronExpression.isValidExpression(form.getCronExpression().trim())) {
            throw new IllegalArgumentException("error.etl.cron.invalid");
        }
        if (form.getTimeoutSeconds() != null && form.getTimeoutSeconds() < 0) {
            throw new IllegalArgumentException("error.etl.timeout.invalid");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> requireSelection(List<String> tables) {
        Set<String> chosen = new LinkedHashSet<>();
        if (tables != null) {
            for (String table : tables) {
                if (table != null && !table.isBlank()) {
                    chosen.add(table.trim());
                }
            }
        }
        if (chosen.isEmpty()) {
            throw new EtlBatchTableException("error.etl.batch.tables.none", List.of());
        }
        return List.copyOf(chosen);
    }

    /**
     * Checks the selection against the live catalog and returns the tables in catalog order, so
     * members are created in a stable order. Every problem is reported with the names that caused
     * it; nothing is skipped.
     */
    private static List<String> validateTables(List<String> chosen,
                                               ConnectionCatalogService.Tables catalog,
                                               Set<String> takenTargets,
                                               DatabaseConfig targetConfig) {
        List<String> known = catalog.tables() == null ? List.of() : catalog.tables();
        Set<String> knownSet = new LinkedHashSet<>(known);

        List<String> unknown = chosen.stream().filter(name -> !knownSet.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new EtlBatchTableException("error.etl.batch.tables.unknown", unknown);
        }

        List<String> invalid = chosen.stream().filter(name -> !isUsableTargetTable(name)).toList();
        if (!invalid.isEmpty()) {
            throw new EtlBatchTableException("error.etl.batch.tables.invalid", invalid);
        }

        // Resolve the target the same way the runner does. A member stored as "SCHEMA.a"
        // and a catalog name "a" are the same physical table; comparing raw text would let
        // both into the batch.
        List<String> existing = chosen.stream()
                .filter(name -> takenTargets.contains(
                        EtlSqlSupport.targetKey(targetConfig, name)))
                .toList();
        if (!existing.isEmpty()) {
            throw new EtlBatchTableException("error.etl.batch.tables.exists", existing);
        }

        return known.stream().filter(chosen::contains).toList();
    }

    private DatabaseConfig targetConfig(Long targetDbId) {
        return databaseConfigService.findById(targetDbId)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.target.missing"));
    }

    private static boolean isUsableTargetTable(String name) {
        return name.length() <= 256 && EtlSqlSupport.PLAIN_IDENTIFIER.matcher(name).matches();
    }

    private void recordRun(EtlBatch batch, int members, int ok, int failed, int skipped,
                           long rowsRead, long rowsWritten) {
        // Re-read: the batch was loaded before the members ran, and a settings edit meanwhile
        // bumps @Version. Writing the pre-run snapshot would lose that edit or fail the merge,
        // turning a run that finished every member into "crashed outside the member loop".
        Long batchId = batch.getId();
        batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            log.debug("ETL batch {} was deleted during its run; nothing to record", batchId);
            return;
        }
        // A skipped member means its table was not refreshed, so the batch is not a clean success
        // even though nothing failed.
        boolean success = failed == 0 && skipped == 0;
        batch.setLastRunTime(Instant.now());
        batch.setLastRunSuccess(success);
        batch.setLastRunResult(success
                ? "SUCCESS members=" + members + " read=" + rowsRead + " written=" + rowsWritten
                : "PARTIAL members=" + members + " ok=" + ok + " failed=" + failed
                + " skipped=" + skipped + " read=" + rowsRead + " written=" + rowsWritten);
        batchRepository.save(batch);
    }

    /**
     * Records a batch failure that happened before the member loop could write its summary.
     * Defensive on purpose: the caller is already reporting a crash, and a failing write must
     * not turn it into a second one.
     */
    void recordCrash(Long batchId, String detail) {
        try {
            batchRepository.findById(batchId).ifPresent(batch -> {
                batch.setLastRunTime(Instant.now());
                batch.setLastRunSuccess(false);
                batch.setLastRunResult("FAILED error=" + detail);
                batchRepository.save(batch);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record the ETL batch {} failure: {}", batchId, e.getMessage());
        }
    }

    private List<EtlTask> buildMembers(EtlBatch batch, List<String> tables, String schema,
                                       DatabaseConfig source) {
        SqlDialect dialect = dialectFactory.forType(source.effectiveType(), null, source.resolvedFamily());
        List<EtlTask> created = new ArrayList<>(tables.size());
        for (String table : tables) {
            EtlTask task = new EtlTask();
            task.setName(table);
            task.setBatchId(batch.getId());
            task.setSourceDbId(batch.getSourceDbId());
            task.setTargetDbId(batch.getTargetDbId());
            task.setSourceSql(EtlBatchTableSql.selectAll(dialect, schema, table));
            task.setTargetTable(table);
            task.setWriteMode(EtlWriteMode.OVERWRITE);
            task.setEnabled(false);
            task.setTimeoutSeconds(batch.getTimeoutSeconds());
            created.add(task);
        }
        return created;
    }
}
