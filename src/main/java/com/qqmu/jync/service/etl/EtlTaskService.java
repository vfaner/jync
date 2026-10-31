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
    public EtlTask save(EtlTask form) {
        validate(form);

        EtlTask entity;
        boolean extractionChanged = true;
        if (form.getId() == null) {
            entity = new EtlTask();
            entity.setEnabled(false);
        } else {
            entity = taskRepository.findById(form.getId())
                    .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
            extractionChanged = extractionChanged(entity, form);
        }

        // Membership is set only by the batch service; a form must never move a task into or out
        // of a batch. A member's connections and schedule belong to the batch.
        boolean member = entity.getBatchId() != null;
        if (member) {
            if (!Objects.equals(entity.getSourceDbId(), form.getSourceDbId())
                    || !Objects.equals(entity.getTargetDbId(), form.getTargetDbId())) {
                throw new IllegalArgumentException("error.etl.batch.connectionsLocked");
            }
            if (form.getTargetTable() != null
                    && taskRepository.existsByBatchIdAndTargetTableAndIdNot(
                            entity.getBatchId(), form.getTargetTable().trim(), entity.getId())) {
                throw new IllegalArgumentException("error.etl.batch.duplicateTarget");
            }
        }

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
        entity.setPreSql(trimToNull(form.getPreSql()));
        entity.setPostSql(trimToNull(form.getPostSql()));
        // A member never schedules itself: the batch owns the cron and the enabled flag.
        entity.setCronExpression(member ? null : trimToNull(form.getCronExpression()));
        if (member) {
            entity.setEnabled(false);
        }
        entity.setTimeoutSeconds(form.getTimeoutSeconds());

        EtlTask saved = taskRepository.save(entity);
        if (saved.getId() != null && extractionChanged) {
            // A resume position only means something for the SQL/table it was produced by.
            checkpointStore.clear(saved.getId());
        }
        events.publishEvent(new EtlTaskChangedEvent(saved.getId(), false));
        return saved;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        if (task.getBatchId() != null) {
            throw new IllegalStateException("error.etl.batch.managed");
        }
        if (enabled && (task.getCronExpression() == null
                || task.getCronExpression().isBlank())) {
            throw new IllegalArgumentException("error.etl.cron.required");
        }
        task.setEnabled(enabled);
        taskRepository.save(task);
        events.publishEvent(new EtlTaskChangedEvent(id, false));
    }

    @Transactional
    public void delete(Long id) {
        EtlTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.etl.not.found"));
        if (task.getBatchId() != null) {
            throw new IllegalStateException("error.etl.batch.managed");
        }
        if (guard.isRunning(id)) {
            throw new IllegalStateException("error.etl.running");
        }
        checkpointStore.clear(id);
        taskRepository.deleteById(id);
        events.publishEvent(new EtlTaskChangedEvent(id, true));
    }

    /**
     * Queues one run and returns immediately. Returns false when the task is already running
     * or no executor slot is free, so the caller can tell the operator nothing was started.
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

        record(task, result);
        return result;
    }

    /**
     * Records a failure the batch runner caught outside the copy loop, so a crashed member still
     * leaves a failed last-run and one failed history row rather than appearing never to have run.
     */
    void recordFailure(Long taskId, String detail) {
        taskRepository.findById(taskId).ifPresent(task ->
                record(task, EtlRunResult.failure(0, 0, EtlRunResult.Side.PROGRAM, detail)));
    }

    private void record(EtlTask task, EtlRunResult result) {
        Instant finishedAt = Instant.now();
        task.setLastRunTime(finishedAt);
        task.setLastRunSuccess(result.success());
        task.setLastRunResult(result.success()
                ? "SUCCESS read=" + result.rowsRead() + " written=" + result.rowsWritten()
                : "FAILED side=" + result.failureSide() + " error=" + result.errorMessage());
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
        return !Objects.equals(before.getSourceDbId(), after.getSourceDbId())
                || !Objects.equals(before.getTargetDbId(), after.getTargetDbId())
                // A blank SQL and a missing one are the same absence: a Mongo task stores ""
                // and its edit form sends null, which must not read as an extraction change.
                || !Objects.equals(trimToNull(before.getSourceSql()), trimToNull(after.getSourceSql()))
                || !Objects.equals(trim(before.getSourceCollection()), trim(after.getSourceCollection()))
                || !Objects.equals(trimToNull(before.getSourceFilter()), trimToNull(after.getSourceFilter()))
                || !Objects.equals(trim(before.getTargetTable()), trim(after.getTargetTable()))
                || before.getWriteMode() != after.getWriteMode()
                || !Objects.equals(trim(before.getRangeCondition()), trim(after.getRangeCondition()))
                || !Objects.equals(trim(before.getResumeKeyColumn()), trim(after.getResumeKeyColumn()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
