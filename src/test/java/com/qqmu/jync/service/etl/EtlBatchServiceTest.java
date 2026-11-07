package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * Batch settings are the head of the group: name, one connection pair, one cron. The rules that
 * matter are the ones a form can violate after the batch already owns members — moving the pair
 * silently, enabling an empty group, or deleting a batch whose tables are mid-run.
 */
class EtlBatchServiceTest {

    private final EtlBatchRepository batchRepository = mock(EtlBatchRepository.class);
    private final EtlTaskRepository taskRepository = mock(EtlTaskRepository.class);
    private final DatabaseConfigService databaseConfigService = mock(DatabaseConfigService.class);
    private final EtlTaskService taskService = mock(EtlTaskService.class);
    private final EtlCheckpointStore checkpointStore = mock(EtlCheckpointStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final EtlExecutionGuard guard = new EtlExecutionGuard();
    private final com.qqmu.jync.service.metadata.ConnectionCatalogService catalogService =
            mock(com.qqmu.jync.service.metadata.ConnectionCatalogService.class);
    private final com.qqmu.jync.service.converter.SqlDialectFactory dialectFactory =
            mock(com.qqmu.jync.service.converter.SqlDialectFactory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private EtlBatchService service;

    @BeforeEach
    void setUp() {
        service = new EtlBatchService(batchRepository, taskRepository, databaseConfigService,
                taskService, checkpointStore, dataSourceManager, guard, catalogService,
                dialectFactory, Runnable::run, events);
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> service.saveSettings(new EtlBatch()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.name.required");
        verify(batchRepository, never()).save(any());
    }

    @Test
    void nameIsCapped() {
        EtlBatch form = form();
        form.setName("x".repeat(129));

        assertThatThrownBy(() -> service.saveSettings(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.name.tooLong");
    }

    @Test
    void bothConnectionsAreRequired() {
        EtlBatch form = form();
        form.setSourceDbId(null);

        assertThatThrownBy(() -> service.saveSettings(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.connection.required");
    }

    @Test
    void theSourceAndTargetMustDiffer() {
        DatabaseConfig same = config(1L, DatabaseType.MYSQL);
        when(databaseConfigService.findById(1L)).thenReturn(Optional.of(same));
        EtlBatch form = form();
        form.setTargetDbId(1L);

        assertThatThrownBy(() -> service.saveSettings(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.same.database");
    }

    @Test
    void aReadOnlyConnectionCannotBeTheBatchTarget() {
        DatabaseConfig source = config(1L, DatabaseType.MYSQL);
        DatabaseConfig target = config(2L, DatabaseType.MONGODB);
        when(databaseConfigService.findById(1L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.saveSettings(form()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.target.readOnly");
    }

    @Test
    void anInvalidCronIsRejected() {
        EtlBatch form = form();
        form.setCronExpression("every night");

        assertThatThrownBy(() -> service.saveSettings(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.cron.invalid");
    }

    @Test
    void enablingRequiresACron() {
        EtlBatch batch = batch(5L);
        batch.setCronExpression(null);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));

        assertThatThrownBy(() -> service.setEnabled(5L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.cron.required");
    }

    @Test
    void enablingRequiresAtLeastOneTable() {
        EtlBatch batch = batch(5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.setEnabled(5L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.empty");
    }

    @Test
    void editingSettingsNeverMovesTheBatchToOtherConnections() {
        EtlBatch stored = batch(5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(stored));
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlBatch edit = form();
        edit.setId(5L);
        edit.setName("renamed");
        edit.setSourceDbId(3L);
        edit.setTargetDbId(4L);

        EtlBatch saved = service.saveSettings(edit);

        assertThat(saved.getSourceDbId()).isEqualTo(1L);
        assertThat(saved.getTargetDbId()).isEqualTo(2L);
        assertThat(saved.getName()).isEqualTo("renamed");
    }

    /**
     * Clearing the cron of a running batch leaves {@code enabled=true} on a batch the scheduler
     * just cancelled, and nothing re-registers it: the page keeps claiming the batch is scheduled
     * while it silently never runs again. Enabling requires a cron, so editing must not remove it.
     */
    @Test
    void anEnabledBatchCannotLoseItsCron() {
        EtlBatch stored = batch(5L);
        stored.setEnabled(true);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(stored));

        EtlBatch edit = form();
        edit.setId(5L);
        edit.setDescription("renamed");
        edit.setCronExpression(" ");

        assertThatThrownBy(() -> service.saveSettings(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.cron.required");
        verify(batchRepository, never()).save(any());
    }

    /**
     * A batch run loaded its member list when it started; deleting a member mid-run makes the
     * runner execute a row that no longer exists and clears the checkpoint of a task that is
     * still writing one. {@link EtlBatchService#delete} refuses in this state; so must removal.
     */
    @Test
    void removingAMemberIsRefusedWhileTheBatchRuns() {
        EtlBatch batch = batch(5L);
        EtlTask member = member(7L, 5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findById(7L)).thenReturn(Optional.of(member));
        guard.tryAcquire("batch:5");

        assertThatThrownBy(() -> service.removeMember(5L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.running");
        verify(taskRepository, never()).delete(any());
    }

    @Test
    void deletingABatchRemovesItsMembersAndTheirCheckpoints() {
        EtlBatch batch = batch(5L);
        EtlTask first = member(7L, 5L);
        EtlTask second = member(8L, 5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of(first, second));

        service.delete(5L);

        verify(checkpointStore).clear(7L);
        verify(checkpointStore).clear(8L);
        verify(taskRepository).deleteAll(List.of(first, second));
        verify(batchRepository).delete(batch);
        verify(events).publishEvent(any(EtlBatchChangedEvent.class));
    }

    @Test
    void deletingIsRefusedWhileAMemberRuns() {
        EtlBatch batch = batch(5L);
        EtlTask running = member(7L, 5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of(running));
        guard.tryAcquire(7L);

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.memberRunning");
        verify(batchRepository, never()).delete(any());
    }

    @Test
    void deletingIsRefusedWhileTheBatchItselfRuns() {
        EtlBatch batch = batch(5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        guard.tryAcquire("batch:5");

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.running");
    }

    private void stubConnections() {
        when(databaseConfigService.findById(1L))
                .thenReturn(Optional.of(config(1L, DatabaseType.MYSQL)));
        when(databaseConfigService.findById(2L))
                .thenReturn(Optional.of(config(2L, DatabaseType.MYSQL)));
    }

    private static DatabaseConfig config(Long id, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(type);
        return config;
    }

    private static EtlBatch form() {
        EtlBatch batch = new EtlBatch();
        batch.setName("nightly");
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        batch.setCronExpression("0 0 3 * * *");
        return batch;
    }

    private static EtlBatch batch(Long id) {
        EtlBatch batch = form();
        batch.setId(id);
        return batch;
    }

    private static EtlTask member(Long id, Long batchId) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setBatchId(batchId);
        return task;
    }
}
