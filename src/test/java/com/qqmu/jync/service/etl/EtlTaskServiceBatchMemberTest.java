package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * A batch member is a task whose schedule and connections belong to the batch. The task form
 * must not be able to move it to another pair of connections, give it its own cron, or delete
 * it behind the batch's back.
 */
@ExtendWith(MockitoExtension.class)
class EtlTaskServiceBatchMemberTest {

    @Mock private EtlTaskRepository taskRepository;
    @Mock private EtlRunRepository runRepository;
    @Mock private DatabaseConfigService databaseConfigService;
    @Mock private EtlRunner runner;
    @Mock private EtlCheckpointStore checkpointStore;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ApplicationEventPublisher events;

    private EtlTaskService service;

    @BeforeEach
    void setUp() {
        service = new EtlTaskService(taskRepository, runRepository, databaseConfigService, runner,
                checkpointStore, dataSourceManager, new EtlExecutionGuard(), Runnable::run, events);
    }

    @Test
    void savingAMemberKeepsTheBatchesConnections() {
        stubConnections(3L);
        stubConnections(4L);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(member(5L)));

        EtlTask edit = form(3L, 4L);
        edit.setId(5L);

        assertThatThrownBy(() -> service.save(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.connectionsLocked");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void savingAMemberDropsItsOwnCronAndEnabled() {
        stubConnections(1L);
        stubConnections(2L);
        EtlTask existing = member(5L);
        existing.setEnabled(false);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask edit = form(1L, 2L);
        edit.setId(5L);
        edit.setCronExpression("0 0 3 * * *");
        edit.setEnabled(true);

        service.save(edit);

        ArgumentCaptor<EtlTask> saved = ArgumentCaptor.forClass(EtlTask.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getCronExpression()).isNull();
        assertThat(saved.getValue().getEnabled()).isFalse();
    }

    @Test
    void aMemberCannotBeEnabledOrDisabledDirectly() {
        when(taskRepository.findById(5L)).thenReturn(Optional.of(member(5L)));

        assertThatThrownBy(() -> service.setEnabled(5L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.managed");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void aMemberCannotBeDeletedThroughTheTaskEndpoint() {
        when(taskRepository.findById(5L)).thenReturn(Optional.of(member(5L)));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.managed");
        verify(taskRepository, never()).deleteById(any());
    }

    @Test
    void aMemberCannotShareAnotherMembersTargetTable() {
        stubConnections(1L);
        stubConnections(2L);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(member(5L)));
        when(taskRepository.findByBatchIdOrderByIdAsc(9L))
                .thenReturn(java.util.List.of(member(5L), member(6L)));

        EtlTask edit = form(1L, 2L);
        edit.setId(5L);

        assertThatThrownBy(() -> service.save(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.duplicateTarget");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void aMemberCannotShareAnotherMembersTargetTableThroughASchemaQualifiedSpelling() {
        // The batch stores bare catalog names, but an operator may edit one member via the
        // task form and write "SCHEMA.dst". A plain string comparison then misses that the
        // next member's "dst" resolves to the same physical table, and two members overwrite
        // each other's rows.
        stubConnections(1L);
        stubTargetSchema(2L, "SCHEMA");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(member(5L)));
        EtlTask sibling = member(6L);
        sibling.setTargetTable("SCHEMA.dst");
        when(taskRepository.findByBatchIdOrderByIdAsc(9L))
                .thenReturn(java.util.List.of(member(5L), sibling));

        EtlTask edit = form(1L, 2L);
        edit.setId(5L);

        assertThatThrownBy(() -> service.save(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.duplicateTarget");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void aFormCannotHijackABatchMembership() {
        stubConnections(1L);
        stubConnections(2L);
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask created = form(1L, 2L);
        created.setBatchId(99L);

        service.save(created);

        ArgumentCaptor<EtlTask> saved = ArgumentCaptor.forClass(EtlTask.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getBatchId()).isNull();
    }

    private void stubConnections(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        when(databaseConfigService.findById(id)).thenReturn(Optional.of(config));
    }

    private void stubTargetSchema(Long id, String schema) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        config.setSchemaName(schema);
        when(databaseConfigService.findById(id)).thenReturn(Optional.of(config));
    }

    private static EtlTask member(Long id) {
        EtlTask task = form(1L, 2L);
        task.setId(id);
        task.setBatchId(9L);
        task.setCronExpression(null);
        task.setEnabled(false);
        return task;
    }

    private static EtlTask form(Long sourceId, Long targetId) {
        EtlTask task = new EtlTask();
        task.setName("orders");
        task.setSourceDbId(sourceId);
        task.setTargetDbId(targetId);
        task.setSourceSql("SELECT id FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }
}
