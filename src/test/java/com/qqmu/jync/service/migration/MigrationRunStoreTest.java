package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;

@ExtendWith(MockitoExtension.class)
class MigrationRunStoreTest {

    @Mock private MigrationRunRepository runRepository;
    @Mock private MigrationTaskRepository taskRepository;

    private MigrationRunStore store;

    @BeforeEach
    void setUp() {
        store = new MigrationRunStore(runRepository, taskRepository);
    }

    @Test
    void startWritesARunningRowWithItsTrigger() {
        when(runRepository.save(any(MigrationRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MigrationRun run = store.start(9L, MigrationRunTrigger.SCHEDULE);

        assertThat(run.getMigrationTaskId()).isEqualTo(9L);
        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.RUNNING);
        assertThat(run.getTriggerType()).isEqualTo(MigrationRunTrigger.SCHEDULE);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    void finishWritesTheOutcomeCountsAndTheTaskSummary() {
        MigrationRun run = running(100L, 9L);
        MigrationTask task = task(9L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        MigrationRunResult result = new MigrationRunResult(true, false, 2, 2, 0, 10, 10,
                null, List.of());
        store.finish(100L, 9L, result);

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.SUCCESS);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getIndicesTotal()).isEqualTo(2);
        assertThat(run.getDocumentsRead()).isEqualTo(10);
        assertThat(run.getDocumentsWritten()).isEqualTo(10);
        assertThat(task.getLastRunSuccess()).isTrue();
        assertThat(task.getLastRunResult()).contains("SUCCESS");
        verify(runRepository).save(run);
        verify(taskRepository).save(task);
    }

    @Test
    void aCancelledResultIsStoredAsCancelled() {
        MigrationRun run = running(100L, 9L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));

        store.finish(100L, 9L, new MigrationRunResult(false, true, 1, 0, 0, 3, 2,
                "cancelled", List.of()));

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.CANCELLED);
        assertThat(run.getDocumentsRead()).isEqualTo(3);
        assertThat(run.getDocumentsWritten()).isEqualTo(2);
    }

    @Test
    void aFailedResultIsStoredAsFailedWithItsMessage() {
        MigrationRun run = running(100L, 9L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));

        store.finish(100L, 9L, new MigrationRunResult(false, false, 1, 0, 1, 4, 0,
                "orders: boom", List.of()));

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
        assertThat(run.getMessage()).isEqualTo("orders: boom");
    }

    @Test
    void aTaskDeletedDuringTheRunStillKeepsItsRunHistory() {
        MigrationRun run = running(100L, 9L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        when(taskRepository.findById(9L)).thenReturn(Optional.empty());

        store.finish(100L, 9L, MigrationRunResult.empty());

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.SUCCESS);
        verify(runRepository).save(run);
        verify(taskRepository, never()).save(any(MigrationTask.class));
    }

    @Test
    void progressUpdatesARunningRowButNeverAResurrectedFinishedOne() {
        MigrationRun running = running(100L, 9L);
        MigrationRun finished = running(101L, 9L);
        finished.setStatus(MigrationRunStatus.SUCCESS);
        when(runRepository.findById(100L)).thenReturn(Optional.of(running));
        when(runRepository.findById(101L)).thenReturn(Optional.of(finished));

        store.progress(100L, "orders", 5, 4);
        store.progress(101L, "orders", 99, 99);

        assertThat(running.getCurrentIndex()).isEqualTo("orders");
        assertThat(running.getDocumentsRead()).isEqualTo(5);
        assertThat(running.getDocumentsWritten()).isEqualTo(4);
        verify(runRepository).save(running);
        verify(runRepository, never()).save(finished);
    }

    @Test
    void failRecordsACrashAsAFailedRun() {
        MigrationRun run = running(100L, 9L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));

        store.fail(100L, 9L, "crashed outside the engine: boom");

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.FAILED);
        assertThat(run.getMessage()).contains("crashed outside the engine");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    private static MigrationRun running(long id, long taskId) {
        MigrationRun run = new MigrationRun();
        run.setId(id);
        run.setMigrationTaskId(taskId);
        run.setStatus(MigrationRunStatus.RUNNING);
        run.setTriggerType(MigrationRunTrigger.MANUAL);
        run.setStartedAt(Instant.now());
        return run;
    }

    private static MigrationTask task(long id) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        return task;
    }

    @Test
    void finishWithAMissingRunRowDoesNotThrow() {
        when(runRepository.findById(100L)).thenReturn(Optional.empty());

        store.finish(100L, 9L, MigrationRunResult.empty());

        verify(runRepository, never()).save(any(MigrationRun.class));
    }

    @Test
    void startPersistsWhateverTheRepositoryAssigns() {
        when(runRepository.save(any(MigrationRun.class))).thenAnswer(invocation -> {
            MigrationRun run = invocation.getArgument(0);
            run.setId(100L);
            return run;
        });

        MigrationRun run = store.start(9L, MigrationRunTrigger.MANUAL);

        ArgumentCaptor<MigrationRun> captor = ArgumentCaptor.forClass(MigrationRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(100L);
        assertThat(run.getId()).isEqualTo(100L);
    }
}
