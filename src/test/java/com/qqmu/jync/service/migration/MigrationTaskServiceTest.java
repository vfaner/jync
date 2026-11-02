package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.dto.MigrationTaskForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.AdminAuditService;

@ExtendWith(MockitoExtension.class)
class MigrationTaskServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectNode DEFAULT_OPTIONS = JSON.createObjectNode();

    @Mock private MigrationTaskRepository taskRepository;
    @Mock private MigrationConnectionRepository connectionRepository;
    @Mock private MigrationTypeRegistry registry;
    @Mock private MigrationExecutionGuard guard;
    @Mock private MigrationRunStore runStore;
    @Mock private ApplicationEventPublisher events;
    @Mock private MigrationTypeHandler handler;
    @Mock private AdminAuditService auditService;

    private MigrationTaskService service;
    private MigrationConnection source;
    private MigrationConnection target;

    @BeforeEach
    void setUp() {
        service = new MigrationTaskService(taskRepository, connectionRepository, registry, guard,
                runStore, Runnable::run, events, auditService);
        source = connection(1L, MigrationConnectionKind.ELASTICSEARCH);
        target = connection(2L, MigrationConnectionKind.EASYSEARCH);
        lenient().when(registry.find(MigrationType.ES_TO_EASYSEARCH))
                .thenReturn(Optional.of(handler));
        lenient().when(handler.sourceKinds()).thenReturn(
                Set.of(MigrationConnectionKind.ELASTICSEARCH, MigrationConnectionKind.OPENSEARCH));
        lenient().when(handler.targetKinds()).thenReturn(
                Set.of(MigrationConnectionKind.EASYSEARCH));
        lenient().when(connectionRepository.findById(1L)).thenReturn(Optional.of(source));
        lenient().when(connectionRepository.findById(2L)).thenReturn(Optional.of(target));
    }

    private static MigrationConnection connection(long id, MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName("conn-" + id);
        connection.setKind(kind);
        connection.setBaseUrl("http://localhost:9200");
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        return connection;
    }

    private static MigrationTaskForm form() {
        MigrationTaskForm form = new MigrationTaskForm();
        form.setName("nightly");
        form.setType(MigrationType.ES_TO_EASYSEARCH);
        form.setSourceConnectionId(1L);
        form.setTargetConnectionId(2L);
        return form;
    }

    private static MigrationTask storedTask(long id) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setName("nightly");
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        task.setOptionsJson("{\"writeMode\":\"OVERWRITE\"}");
        task.setEnabled(false);
        return task;
    }

    private void optionsParseTo(String json) throws Exception {
        when(handler.parseOptions(any())).thenReturn(JSON.readTree(json));
    }

    @Test
    void savingANewTaskStoresCanonicalOptionsAndAnnouncesTheChange() throws Exception {
        optionsParseTo("{\"writeMode\":\"APPEND\"}");
        when(taskRepository.save(any(MigrationTask.class))).thenAnswer(invocation -> {
            MigrationTask saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        MigrationTask saved = service.save(form(), DEFAULT_OPTIONS);

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getOptionsJson()).isEqualTo("{\"writeMode\":\"APPEND\"}");
        verify(events).publishEvent(new MigrationTaskChangedEvent(10L, false));
        verify(auditService).record(eq(AuditAction.MIGRATION_TASK_SAVE),
                contains("nightly"));
    }

    @Test
    void aBlankNameIsRejectedBeforeAnythingElseIsRead() {
        MigrationTaskForm form = form();
        form.setName("  ");

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.name.required");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void aNameLongerThanTheColumnIsRejected() {
        MigrationTaskForm form = form();
        form.setName("x".repeat(129));

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.name.tooLong");
    }

    @Test
    void theSourceAndTargetCannotBeTheSameConnection() {
        MigrationTaskForm form = form();
        form.setTargetConnectionId(1L);

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.same.connection");
    }

    @Test
    void aSourceKindTheHandlerDoesNotAcceptIsRejected() {
        source.setKind(MigrationConnectionKind.EASYSEARCH);
        MigrationTaskForm form = form();

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.source.kind");
    }

    @Test
    void aTargetKindTheHandlerDoesNotAcceptIsRejected() {
        target.setKind(MigrationConnectionKind.OPENSEARCH);
        MigrationTaskForm form = form();

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.target.kind");
    }

    @Test
    void anInvalidOptionKeyFromTheHandlerIsPropagated() {
        when(handler.parseOptions(any()))
                .thenThrow(new IllegalArgumentException("error.migration.options.batchSize.invalid"));
        MigrationTaskForm form = form();

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.options.batchSize.invalid");
    }

    @Test
    void anInvalidCronIsRejected() {
        MigrationTaskForm form = form();
        form.setCronExpression("not a cron");

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.cron.invalid");
    }

    @Test
    void enablingWithoutACronIsRejected() {
        MigrationTaskForm form = form();
        form.setEnabled(true);

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.cron.required");
    }

    @Test
    void aNegativeTimeoutIsRejected() {
        MigrationTaskForm form = form();
        form.setTimeoutSeconds(-1);

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.timeout.invalid");
    }

    @Test
    void changingConnectionsOrOptionsWhileRunningIsRejected() throws Exception {
        MigrationTask existing = storedTask(9L);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(guard.isRunning(9L)).thenReturn(true);
        optionsParseTo("{\"writeMode\":\"APPEND\"}");
        MigrationTaskForm form = form();
        form.setId(9L);

        assertThatThrownBy(() -> service.save(form, DEFAULT_OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.migration.running.config");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void editingADescriptionWhileRunningIsAllowed() throws Exception {
        MigrationTask existing = storedTask(9L);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(guard.isRunning(9L)).thenReturn(true);
        optionsParseTo("{\"writeMode\":\"OVERWRITE\"}");
        when(taskRepository.save(any(MigrationTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MigrationTaskForm form = form();
        form.setId(9L);
        form.setDescription("new words");

        MigrationTask saved = service.save(form, DEFAULT_OPTIONS);

        assertThat(saved.getDescription()).isEqualTo("new words");
    }

    @Test
    void deletingARunningTaskIsRejected() {
        when(taskRepository.findById(9L)).thenReturn(Optional.of(storedTask(9L)));
        when(guard.isRunning(9L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.migration.running");
        verify(taskRepository, never()).deleteById(9L);
    }

    @Test
    void deletingAStoppedTaskAnnouncesTheDeletion() {
        when(taskRepository.findById(9L)).thenReturn(Optional.of(storedTask(9L)));
        when(guard.isRunning(9L)).thenReturn(false);

        service.delete(9L);

        verify(taskRepository).deleteById(9L);
        verify(events).publishEvent(new MigrationTaskChangedEvent(9L, true));
        verify(auditService).record(eq(AuditAction.MIGRATION_TASK_DELETE),
                contains("nightly"));
    }

    @Test
    void submitStartsARunAndRecordsTheEngineResult() {
        MigrationTask task = storedTask(9L);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(guard.tryAcquire(9L)).thenReturn(true);
        MigrationCancellation token = new MigrationCancellation();
        when(guard.cancellation(9L)).thenReturn(token);
        MigrationRun run = new MigrationRun();
        run.setId(100L);
        when(runStore.start(9L, MigrationRunTrigger.MANUAL)).thenReturn(run);
        MigrationRunResult result = new MigrationRunResult(true, false, 1, 1, 0, 2, 2,
                null, List.of());
        when(handler.run(any(), any(), any(), any(), any(), any())).thenReturn(result);

        assertThat(service.submit(9L, MigrationRunTrigger.MANUAL)).isTrue();

        verify(runStore).finish(100L, 9L, result);
        verify(guard).release(9L);
    }

    @Test
    void aSecondSubmitWhileRunningStartsNothing() {
        when(guard.tryAcquire(9L)).thenReturn(false);

        assertThat(service.submit(9L, MigrationRunTrigger.SCHEDULE)).isFalse();

        verify(runStore, never()).start(any(), any());
    }

    @Test
    void aRejectedSubmissionReleasesTheGuardAndFailsTheRun() {
        when(guard.tryAcquire(9L)).thenReturn(true);
        MigrationRun run = new MigrationRun();
        run.setId(100L);
        when(runStore.start(9L, MigrationRunTrigger.MANUAL)).thenReturn(run);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("full");
        };
        service = new MigrationTaskService(taskRepository, connectionRepository, registry, guard,
                runStore, rejecting, events, auditService);

        assertThatThrownBy(() -> service.submit(9L, MigrationRunTrigger.MANUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.migration.queue.full");

        verify(guard).release(9L);
        verify(runStore).fail(100L, 9L, "error.migration.queue.full");
    }

    @Test
    void anEngineCrashOutsideItsOwnHandlingIsRecordedAsFailed() {
        MigrationTask task = storedTask(9L);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(guard.tryAcquire(9L)).thenReturn(true);
        when(guard.cancellation(9L)).thenReturn(new MigrationCancellation());
        MigrationRun run = new MigrationRun();
        run.setId(100L);
        when(runStore.start(9L, MigrationRunTrigger.MANUAL)).thenReturn(run);
        when(handler.run(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(service.submit(9L, MigrationRunTrigger.MANUAL)).isTrue();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(runStore).fail(eq(100L), eq(9L), message.capture());
        assertThat(message.getValue()).contains("crashed outside the engine").contains("boom");
        verify(guard).release(9L);
    }

    @Test
    void cancelDelegatesToTheGuardAndReportsWhetherAnythingWasRunning() {
        when(guard.cancel(9L)).thenReturn(true);

        assertThat(service.cancel(9L)).isTrue();

        verify(guard).cancel(9L);
    }

    @Test
    void setEnabledRefusesToTurnOnACronlessTask() {
        MigrationTask task = storedTask(9L);
        task.setCronExpression(null);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.setEnabled(9L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.cron.required");
    }

    @Test
    void setEnabledSavesAndAnnouncesTheChange() {
        MigrationTask task = storedTask(9L);
        task.setCronExpression("0 */5 * * * *");
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        service.setEnabled(9L, true);

        assertThat(task.getEnabled()).isTrue();
        verify(taskRepository).save(task);
        verify(events).publishEvent(new MigrationTaskChangedEvent(9L, false));
    }

    @Test
    void anUnknownTypeCannotBeSaved() {
        when(registry.find(MigrationType.ES_TO_EASYSEARCH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(form(), DEFAULT_OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.migration.type.unknown");
    }
}
