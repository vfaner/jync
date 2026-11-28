package com.qqmu.jync.service.etl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.auth.Ownership;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlRun;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/** CRUD, validation and execution entry points for ETL tasks. */
@Service
public class EtlTaskService {

    private static final Logger log = LoggerFactory.getLogger(EtlTaskService.class);

    private final EtlTaskRepository taskRepository;
    private final EtlRunRepository runRepository;
    private final DatabaseConfigService databaseConfigService;
    private final EtlRunner runner;
    private final EtlCheckpointStore checkpointStore;
    private final DataSourceManager dataSourceManager;
    private final EtlExecutionGuard guard;
    private final Executor executor;
    private final ApplicationEventPublisher events;

    public EtlTaskService(EtlTaskRepository taskRepository,
                          EtlRunRepository runRepository,
                          DatabaseConfigService databaseConfigService,
                          EtlRunner runner,
                          EtlCheckpointStore checkpointStore,
                          DataSourceManager dataSourceManager,
                          EtlExecutionGuard guard,
                          @Qualifier("etlExecutor") Executor executor,
                          ApplicationEventPublisher events) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.databaseConfigService = databaseConfigService;
        this.runner = runner;
        this.checkpointStore = checkpointStore;
        this.dataSourceManager = dataSourceManager;
        this.guard = guard;
        this.executor = executor;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<EtlTask> findAll() {
        return taskRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Optional<EtlTask> findById(Long id) {
        return taskRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<DatabaseConfig> connections() {
        return databaseConfigService.findAll();
    }

    public boolean isRunning(Long id) {
        return guard.isRunning(id);
    }

    @Transactional
    public EtlTask save(EtlTask form, AccessGuard actor) {
        validate(form);

        EtlTask entity;
        boolean extractionChanged = true;
        EtlExecutionGuard.MutationLease extractionLease = null;
        if (form.getId() == null) {
            entity = new EtlTask();
            entity.setEnabled(false);
            entity.setOwnerUsername(actor.username());
        } else {
            entity = taskRepository.findById(form.getId())
                    .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
            actor.requireEditable(entity.getOwnerUsername());
            extractionChanged = extractionChanged(entity, form);
            if (extractionChanged) {
                // The runner read its SQL, target and resume key when it started. Clearing the
                // checkpoint now would be undone by the old query's next checkpoint write, and
                // the next run could resume from a watermark the new query never produced.
                // The lease is held until this transaction commits, so a run cannot start
                // between the check and the moment the edit becomes visible.
                extractionLease = guard.lockForMutation(form.getId());
                if (!extractionLease.acquired()) {
                    throw new IllegalStateException("error.etl.running.extraction");
                }
            }
        }

        // Membership is set only by the batch service; a form must never move a task into or out
        // of a batch. A member's connections and schedule belong to the batch.
        boolean member = entity.getBatchId() != null;
        if (!member && Boolean.TRUE.equals(entity.getEnabled())
                && (form.getCronExpression() == null || form.getCronExpression().isBlank())) {
            // setEnabled refuses to enable without a cron; editing must not produce that state,
            // or the scheduler cancels a task the page still shows as scheduled.
            throw new IllegalArgumentException("error.etl.cron.required");
        }
        if (member) {
            if (!Objects.equals(entity.getSourceDbId(), form.getSourceDbId())
                    || !Objects.equals(entity.getTargetDbId(), form.getTargetDbId())) {
                throw new IllegalArgumentException("error.etl.batch.connectionsLocked");
            }
            if (form.getTargetTable() != null) {
                // Compare the resolved target, not the typed text: a member may have been
                // saved as "SCHEMA.dst" while this one says "dst", and both write the same
                // physical table. Case is folded the same way in the batch service.
                DatabaseConfig memberTarget = databaseConfigService
                        .findById(entity.getTargetDbId())
                        .orElseThrow(() -> new IllegalArgumentException("error.etl.target.missing"));
                String requested = EtlSqlSupport.targetKey(memberTarget, form.getTargetTable());
                boolean duplicate = taskRepository.findByBatchIdOrderByIdAsc(entity.getBatchId())
                        .stream()
                        .filter(sibling -> !Objects.equals(sibling.getId(), entity.getId()))
                        .anyMatch(sibling -> sibling.getTargetTable() != null
                                && requested.equals(EtlSqlSupport.targetKey(
                                        memberTarget, sibling.getTargetTable())));
                if (duplicate) {
                    throw new IllegalArgumentException("error.etl.batch.duplicateTarget");
                }
            }
        }

        // The form may name any existing connection; only the caller's own are a legal
        // reference. validate() above already refused a missing one.
        databaseConfigService.findById(form.getSourceDbId())
                .ifPresent(db -> Ownership.requireReferenceable(
                        db.getOwnerUsername(), db.getName(), actor));
        databaseConfigService.findById(form.getTargetDbId())
                .ifPresent(db -> Ownership.requireReferenceable(
                        db.getOwnerUsername(), db.getName(), actor));

        entity.setName(form.getName().trim());
        entity.setDescription(trimToNull(form.getDescription()));
        entity.setSourceDbId(form.getSourceDbId());
        entity.setTargetDbId(form.getTargetDbId());
        if (nativeMongo(form.getSourceDbId())) {
            // A native Mongo task has no extraction SQL, but the column is NOT NULL in
            // existing installations, so it stores the empty string instead.
            entity.setSourceSql("");
            entity.setSourceCollection(form.getSourceCollection().trim());
            entity.setSourceFilter(trimToNull(form.getSourceFilter()));
        } else {
            entity.setSourceSql(form.getSourceSql() == null ? "" : form.getSourceSql().trim());
            // Switching back to a SQL source must not leave a Mongo collection behind.
            entity.setSourceCollection(null);
            entity.setSourceFilter(null);
        }
        entity.setTargetTable(form.getTargetTable().trim());
        entity.setWriteMode(form.getWriteMode() == null
                ? EtlWriteMode.OVERWRITE : form.getWriteMode());
        entity.setRangeCondition(trimToNull(form.getRangeCondition()));
        entity.setResumeKeyColumn(trimToNull(form.getResumeKeyColumn()));
        entity.setUpsertKeyColumns(trimToNull(form.getUpsertKeyColumns()));
        entity.setPreSql(trimToNull(form.getPreSql()));
        entity.setPostSql(trimToNull(form.getPostSql()));
        entity.setVariables(trimToNull(form.getVariables()));
        // A member never schedules itself: the batch owns the cron and the enabled flag.
        entity.setCronExpression(member ? null : trimToNull(form.getCronExpression()));
        if (member) {
            entity.setEnabled(false);
        }
        entity.setTimeoutSeconds(form.getTimeoutSeconds());
        if (extractionChanged) {
            // A new task or a changed extraction is a new series: its first run reads 0.
            // ${runIndex} answers "how many runs has this query had", not "how many has this
            // row" — carrying the old query's count into an unrelated one would be a lie.
            entity.setRunIndex(0);
        }

        EtlTask saved;
        try {
            saved = taskRepository.save(entity);
            if (saved.getId() != null && extractionChanged) {
                // A resume position only means something for the SQL/table it was produced by.
                checkpointStore.clear(saved.getId());
            }
        } finally {
            // No-op inside a transaction (it releases in afterCompletion, after commit);
            // releases immediately for a hand-built caller without one.
            if (extractionLease != null) {
                extractionLease.close();
            }
        }
        events.publishEvent(new EtlTaskChangedEvent(saved.getId(), false));
        return saved;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled, AccessGuard actor) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        actor.requireEditable(task.getOwnerUsername());
        if (task.getBatchId() != null) {
            throw new IllegalStateException("error.etl.batch.managed");
        }
        if (enabled) {
            if (task.getCronExpression() == null || task.getCronExpression().isBlank()) {
                throw new IllegalArgumentException("error.etl.cron.required");
            }
            // Save rejects an invalid cron, but a row written before that check existed can
            // still carry one. The scheduler would refuse its trigger and only log a warning,
            // leaving the page showing a task that is "enabled" and never fires — refuse here,
            // where the operator can see why.
            if (!CronExpression.isValidExpression(task.getCronExpression().trim())) {
                throw new IllegalArgumentException("error.etl.cron.invalid");
            }
        }
        task.setEnabled(enabled);
        taskRepository.save(task);
        events.publishEvent(new EtlTaskChangedEvent(id, false));
    }

    @Transactional
    public void delete(Long id, AccessGuard actor) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        actor.requireEditable(task.getOwnerUsername());
        if (task.getBatchId() != null) {
            throw new IllegalStateException("error.etl.batch.managed");
        }
        // The key is held until commit, not checked and dropped: a run starting after a plain
        // isLeased() check would have its checkpoint cleared under it and its lease row
        // discarded by the delete. A start during the delete now fails instead.
        try (EtlExecutionGuard.MutationLease lease = guard.lockForMutation(id)) {
            if (!lease.acquired()) {
                throw new IllegalStateException("error.etl.running");
            }
            checkpointStore.clear(id);
            taskRepository.deleteById(id);
        }
        events.publishEvent(new EtlTaskChangedEvent(id, true));
    }

    /** Loads the task and refuses it unless the caller may edit it. */
    public EtlTask requireEditable(Long id, AccessGuard actor) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        actor.requireEditable(task.getOwnerUsername());
        return task;
    }

    /**
     * Queues one run and returns immediately. Returns false when the task is already running
     * or no executor slot is free, so the caller can tell the operator nothing was started.
     *
     * <p>No actor: the scheduler submits too, and it has no request identity. Callers that
     * answer a request must ask {@link #requireEditable(Long, AccessGuard)} first.
     */
    public boolean submit(Long id) {
        if (!guard.tryAcquire(id)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(id);
                } catch (RuntimeException e) {
                    log.warn("ETL task {} crashed outside the copy loop: {}", id, e.getMessage());
                    // Logging alone would leave the previous successful run on display, as if
                    // this trigger had never happened.
                    recordFailure(id, "crashed outside the copy loop: " + e);
                } finally {
                    guard.release(id);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // The queue is full. Releasing the guard matters more than the message: otherwise
            // the task would read as running until restart.
            guard.release(id);
            throw new IllegalStateException("error.etl.queue.full");
        } catch (RuntimeException e) {
            guard.release(id);
            throw e;
        }
    }

    /** Runs synchronously; used by tests and by the submit wrapper above. */
    public EtlRunResult execute(Long id) {
        try (EtlConnections connections = new EtlConnections(dataSourceManager)) {
            return executeWithin(id, connections);
        }
    }

    /**
     * Runs one task on connections owned by the caller. A batch creates one
     * {@link EtlConnections} for all of its members, so the workers share the source and target
     * pools instead of opening a fresh pair per table.
     */
    public EtlRunResult executeWithin(Long id, EtlConnectionProvider connections) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        EtlRunResult result;
        try {
            DatabaseConfig source = databaseConfigService.findById(task.getSourceDbId())
                    .orElseThrow(() -> new IllegalArgumentException("error.etl.source.missing"));
            DatabaseConfig target = databaseConfigService.findById(task.getTargetDbId())
                    .orElseThrow(() -> new IllegalArgumentException("error.etl.target.missing"));
            result = runner.run(task, source, target, connections);
        } catch (IllegalArgumentException e) {
            result = EtlRunResult.failure(0, 0, EtlRunResult.Side.PROGRAM, e.getMessage());
        }

        record(id, result);
        return result;
    }

    /**
     * Records a failure the batch runner caught outside the copy loop, so a crashed member still
     * leaves a failed last-run and one failed history row rather than appearing never to have run.
     */
    void recordFailure(Long taskId, String detail) {
        try {
            record(taskId, EtlRunResult.failure(0, 0, EtlRunResult.Side.PROGRAM, detail));
        } catch (RuntimeException e) {
            // Bookkeeping must not turn one crash into two: the caller is already reporting it.
            log.warn("Could not record the ETL failure for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Applies the outcome to the task row as it stands now, not to the entity the run loaded
     * before it started. A run can last minutes; an edit saved meanwhile bumps {@code @Version},
     * and writing the pre-run snapshot back would then lose the edit or fail the merge — and the
     * caller's crash handler would report a run that copied every row as FAILED.
     */
    private void record(Long taskId, EtlRunResult result) {
        EtlTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.debug("ETL task {} was deleted during its run; nothing to record", taskId);
            return;
        }
        Instant finishedAt = Instant.now();
        if (result.success()) {
            // Only a successful run consumes a number: the retry of a failed run must read the
            // same value the failed attempt did, or the window it never loaded is skipped.
            task.setRunIndex((task.getRunIndex() == null ? 0 : task.getRunIndex()) + 1);
        }
        task.setLastRunTime(finishedAt);
        task.setLastRunSuccess(result.success());
        // Columns the existing target lacks are skipped, not failed; the summary names them so
        // the page shows why fewer columns were written than the extraction selects.
        String skipped = result.skippedColumns().isEmpty()
                ? "" : " skipped=" + String.join(",", result.skippedColumns());
        task.setLastRunResult(result.success()
                ? "SUCCESS read=" + result.rowsRead() + " written=" + result.rowsWritten() + skipped
                : "FAILED side=" + result.failureSide() + skipped
                + " error=" + result.errorMessage());
        taskRepository.save(task);

        // The task row only keeps the latest outcome; the dashboard's ETL line needs the
        // history, so every run leaves one row behind. Failure to write it must not turn a
        // finished run into a failed one.
        EtlRun run = new EtlRun();
        run.setEtlTaskId(task.getId());
        run.setSuccess(result.success());
        run.setAffectedRows((int) Math.min(Integer.MAX_VALUE, result.rowsWritten()));
        run.setOccurredAt(finishedAt);
        try {
            runRepository.save(run);
        } catch (RuntimeException e) {
            log.warn("Could not record the ETL run history for task {}: {}", task.getId(), e.getMessage());
        }
    }

    // --- validation ---------------------------------------------------------------------

    private void validate(EtlTask task) {
        if (task.getName() == null || task.getName().isBlank()) {
            throw new IllegalArgumentException("error.etl.name.required");
        }
        if (task.getName().trim().length() > 128) {
            throw new IllegalArgumentException("error.etl.name.tooLong");
        }
        EtlTaskValidation.validateExtraction(task, databaseConfigService);
        if (task.getCronExpression() != null && !task.getCronExpression().isBlank()
                && !CronExpression.isValidExpression(task.getCronExpression().trim())) {
            throw new IllegalArgumentException("error.etl.cron.invalid");
        }
        if (task.getTimeoutSeconds() != null && task.getTimeoutSeconds() < 0) {
            throw new IllegalArgumentException("error.etl.timeout.invalid");
        }
    }

    private boolean nativeMongo(Long sourceDbId) {
        return EtlTaskValidation.isNativeMongo(
                databaseConfigService.findById(sourceDbId).orElse(null));
    }

    private static boolean extractionChanged(EtlTask before, EtlTask after) {
        boolean renamed = !Objects.equals(trim(before.getName()), trim(after.getName()));
        return (renamed && (usesTaskName(before) || usesTaskName(after)))
                // ${taskName} is a built-in SQL variable: only a task whose SQL actually
                // references it changes its row set on rename. A plain rename must not
                // discard a resume checkpoint or block a running task.
                || !Objects.equals(before.getSourceDbId(), after.getSourceDbId())
                || !Objects.equals(before.getTargetDbId(), after.getTargetDbId())
                // A blank SQL and a missing one are the same absence: a Mongo task stores ""
                // and its edit form sends null, which must not read as an extraction change.
                || !Objects.equals(trimToNull(before.getSourceSql()), trimToNull(after.getSourceSql()))
                || !Objects.equals(trim(before.getSourceCollection()), trim(after.getSourceCollection()))
                || !Objects.equals(trimToNull(before.getSourceFilter()), trimToNull(after.getSourceFilter()))
                // The definitions decide what the query asks for just as much as the SQL does.
                || !Objects.equals(trimToNull(before.getVariables()), trimToNull(after.getVariables()))
                || !Objects.equals(trim(before.getTargetTable()), trim(after.getTargetTable()))
                || before.getWriteMode() != after.getWriteMode()
                || !Objects.equals(trim(before.getRangeCondition()), trim(after.getRangeCondition()))
                || !Objects.equals(trim(before.getResumeKeyColumn()), trim(after.getResumeKeyColumn()))
                || !Objects.equals(trim(before.getUpsertKeyColumns()),
                        trim(after.getUpsertKeyColumns()));
    }

    private static boolean usesTaskName(EtlTask task) {
        return containsTaskName(task.getSourceSql())
                || containsTaskName(task.getPreSql())
                || containsTaskName(task.getPostSql())
                || containsTaskName(task.getRangeCondition())
                || containsTaskName(task.getVariables());
    }

    private static boolean containsTaskName(String value) {
        return value != null && value.contains("${taskName}");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
