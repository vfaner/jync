package com.qqmu.jync.service.migration;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.dto.MigrationTaskForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;

/**
 * CRUD, validation and execution entry points for dedicated-migration tasks.
 *
 * <p>A task stores only what the generic module understands; its handler validates the
 * type-specific options and runs it. One in-memory slot per task prevents overlapping runs.
 */
@Service
public class MigrationTaskService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MigrationTaskRepository taskRepository;
    private final MigrationConnectionRepository connectionRepository;
    private final MigrationTypeRegistry registry;
    private final MigrationExecutionGuard guard;
    private final MigrationRunStore runStore;
    private final Executor executor;
    private final ApplicationEventPublisher events;
    private final AdminAuditService auditService;

    public MigrationTaskService(MigrationTaskRepository taskRepository,
                                MigrationConnectionRepository connectionRepository,
                                MigrationTypeRegistry registry,
                                MigrationExecutionGuard guard,
                                MigrationRunStore runStore,
                                @Qualifier("migrationExecutor") Executor executor,
                                ApplicationEventPublisher events,
                                AdminAuditService auditService) {
        this.taskRepository = taskRepository;
        this.connectionRepository = connectionRepository;
        this.registry = registry;
        this.guard = guard;
        this.runStore = runStore;
        this.executor = executor;
        this.events = events;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<MigrationTask> findByType(MigrationType type) {
        return taskRepository.findByTypeOrderByIdDesc(type);
    }

    @Transactional(readOnly = true)
    public Optional<MigrationTask> findById(Long id) {
        return taskRepository.findById(id);
    }

    public boolean isRunning(Long id) {
        return guard.isRunning(id);
    }

    @Transactional
    public MigrationTask save(MigrationTaskForm form, ObjectNode formOptions) {
        validateBasics(form);
        MigrationTypeHandler handler = registry.find(form.getType())
                .orElseThrow(() -> new IllegalArgumentException("error.migration.type.unknown"));

        if (Objects.equals(form.getSourceConnectionId(), form.getTargetConnectionId())) {
            throw new IllegalArgumentException("error.migration.same.connection");
        }
        MigrationConnection source = connectionRepository.findById(
                        required(form.getSourceConnectionId(), "error.migration.source.missing"))
                .orElseThrow(() -> new IllegalArgumentException("error.migration.source.missing"));
        MigrationConnection target = connectionRepository.findById(
                        required(form.getTargetConnectionId(), "error.migration.target.missing"))
                .orElseThrow(() -> new IllegalArgumentException("error.migration.target.missing"));
        if (!handler.sourceKinds().contains(source.getKind())) {
            throw new IllegalArgumentException("error.migration.source.kind");
        }
        if (!handler.targetKinds().contains(target.getKind())) {
            throw new IllegalArgumentException("error.migration.target.kind");
        }

        JsonNode options = handler.parseOptions(formOptions);
        String cron = trimToNull(form.getCronExpression());
        if (cron != null && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("error.migration.cron.invalid");
        }
        boolean enabled = Boolean.TRUE.equals(form.isEnabled());
        if (enabled && cron == null) {
            throw new IllegalArgumentException("error.migration.cron.required");
        }
        if (form.getTimeoutSeconds() != null && form.getTimeoutSeconds() < 0) {
            throw new IllegalArgumentException("error.migration.timeout.invalid");
        }

        boolean isNew = form.getId() == null;
        MigrationTask entity;
        if (isNew) {
            entity = new MigrationTask();
            entity.setEnabled(false);
        } else {
            entity = taskRepository.findById(form.getId())
                    .orElseThrow(() -> new IllegalArgumentException("error.migration.not.found"));
            if (guard.isRunning(entity.getId())
                    && configurationChanged(entity, form, options)) {
                // The engine already read the old connections and options; letting the form
                // change them mid-run would make the history describe something else.
                throw new IllegalStateException("error.migration.running.config");
            }
        }

        entity.setName(form.getName().trim());
        entity.setDescription(trimToNull(form.getDescription()));
        entity.setType(form.getType());
        entity.setSourceConnectionId(form.getSourceConnectionId());
        entity.setTargetConnectionId(form.getTargetConnectionId());
        entity.setOptionsJson(options.toString());
        entity.setCronExpression(cron);
        entity.setEnabled(enabled);
        entity.setTimeoutSeconds(form.getTimeoutSeconds());

        MigrationTask saved = taskRepository.save(entity);
        auditService.record(AuditAction.MIGRATION_TASK_SAVE,
                AuditDetail.of("audit.detail.migrationTaskSave",
                        isNew ? "@created" : "@updated", saved.getName(),
                        "@msg:" + saved.getType().messageKey()));
        events.publishEvent(new MigrationTaskChangedEvent(saved.getId(), false));
        return saved;
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        MigrationTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.migration.not.found"));
        if (enabled && (task.getCronExpression() == null
                || task.getCronExpression().isBlank())) {
            throw new IllegalArgumentException("error.migration.cron.required");
        }
        task.setEnabled(enabled);
        taskRepository.save(task);
        events.publishEvent(new MigrationTaskChangedEvent(id, false));
    }

    @Transactional
    public void delete(Long id) {
        MigrationTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.migration.not.found"));
        if (guard.isRunning(id)) {
            throw new IllegalStateException("error.migration.running");
        }
        taskRepository.deleteById(id);
        auditService.record(AuditAction.MIGRATION_TASK_DELETE,
                AuditDetail.of("audit.detail.migrationTaskDelete", task.getName(),
                        "@msg:" + task.getType().messageKey()));
        events.publishEvent(new MigrationTaskChangedEvent(id, true));
    }

    /**
     * Queues one run. Returns false when the task already has a run in flight; throws when the
     * executor refuses it, after releasing the slot so the task does not read as running.
     */
    public boolean submit(Long id, MigrationRunTrigger trigger) {
        if (!guard.tryAcquire(id)) {
            return false;
        }
        MigrationRun run;
        try {
            run = runStore.start(id, trigger);
        } catch (RuntimeException e) {
            guard.release(id);
            throw e;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(id, run.getId());
                } catch (RuntimeException e) {
                    runStore.fail(run.getId(), id, "crashed outside the engine: " + e,
                            "crashed outside the engine");
                } finally {
                    guard.release(id);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            guard.release(id);
            runStore.fail(run.getId(), id, "error.migration.queue.full", "queue full");
            throw new IllegalStateException("error.migration.queue.full");
        } catch (RuntimeException e) {
            guard.release(id);
            throw e;
        }
    }

    /** One run; the caller has already acquired the guard and written the RUNNING row. */
    void execute(Long id, Long runId) {
        MigrationTask task = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error.migration.not.found"));
        MigrationTypeHandler handler = registry.find(task.getType())
                .orElseThrow(() -> new IllegalArgumentException("error.migration.type.unknown"));
        MigrationConnection source = connectionRepository.findById(task.getSourceConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("error.migration.source.missing"));
        MigrationConnection target = connectionRepository.findById(task.getTargetConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("error.migration.target.missing"));

        MigrationCancellation cancellation = guard.cancellation(id);
        if (cancellation == null) {
            cancellation = new MigrationCancellation();
        }
        if (task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0) {
            cancellation.setDeadline(Instant.now().plusSeconds(task.getTimeoutSeconds()));
        }

        MigrationRunResult result = handler.run(task, options(task), source, target,
                cancellation, (currentIndex, read, written) ->
                        runStore.progress(runId, currentIndex, read, written));
        runStore.finish(runId, id, result);
    }

    public boolean cancel(Long id) {
        return guard.cancel(id);
    }

    private static JsonNode options(MigrationTask task) {
        if (task.getOptionsJson() == null || task.getOptionsJson().isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(task.getOptionsJson());
        } catch (IOException e) {
            throw new IllegalArgumentException("error.migration.options.invalid", e);
        }
    }

    private static void validateBasics(MigrationTaskForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("error.migration.name.required");
        }
        if (form.getName().trim().length() > 128) {
            throw new IllegalArgumentException("error.migration.name.tooLong");
        }
        if (form.getType() == null) {
            throw new IllegalArgumentException("error.migration.type.unknown");
        }
    }

    private static Long required(Long id, String errorKey) {
        if (id == null) {
            throw new IllegalArgumentException(errorKey);
        }
        return id;
    }

    private static boolean configurationChanged(MigrationTask entity, MigrationTaskForm form,
                                                JsonNode options) {
        return !Objects.equals(entity.getSourceConnectionId(), form.getSourceConnectionId())
                || !Objects.equals(entity.getTargetConnectionId(), form.getTargetConnectionId())
                || entity.getType() != form.getType()
                || !Objects.equals(entity.getOptionsJson(), options.toString());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
