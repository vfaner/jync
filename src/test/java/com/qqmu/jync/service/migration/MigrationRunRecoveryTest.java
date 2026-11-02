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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;

@ExtendWith(MockitoExtension.class)
class MigrationRunRecoveryTest {

    @Mock private MigrationRunRepository runRepository;
    @Mock private MigrationTaskRepository taskRepository;

    private MigrationRunRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new MigrationRunRecovery(runRepository, taskRepository);
    }

    @Test
    void aRunLeftRunningByACrashBecomesInterruptedAndTheTaskShowsIt() {
        MigrationRun run = running(1L, 9L);
        MigrationTask task = task(9L);
        when(runRepository.findByStatus(MigrationRunStatus.RUNNING))
                .thenReturn(List.of(run));
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));

        int recovered = recovery.recoverInterrupted();

        assertThat(recovered).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.INTERRUPTED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getMessage()).isEqualTo("error.migration.interrupted");
        assertThat(task.getLastRunSuccess()).isFalse();
        assertThat(task.getLastRunResult()).isEqualTo("INTERRUPTED");
        assertThat(task.getLastRunTime()).isNotNull();
        verify(runRepository).save(run);
        verify(taskRepository).save(task);
    }

    @Test
    void aRunWhoseTaskWasDeletedIsStillMarkedInterrupted() {
        MigrationRun run = running(1L, 9L);
        when(runRepository.findByStatus(MigrationRunStatus.RUNNING))
                .thenReturn(List.of(run));
        when(taskRepository.findById(9L)).thenReturn(Optional.empty());

        recovery.recoverInterrupted();

        assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.INTERRUPTED);
        verify(runRepository).save(run);
        verify(taskRepository, never()).save(any(MigrationTask.class));
    }

    @Test
    void startupWithNoRunningRowsWritesNothing() {
        when(runRepository.findByStatus(MigrationRunStatus.RUNNING)).thenReturn(List.of());

        int recovered = recovery.recoverInterrupted();

        assertThat(recovered).isZero();
        verify(runRepository, never()).save(any(MigrationRun.class));
    }

    @Test
    void everyRunInTheListIsRecovered() {
        MigrationRun first = running(1L, 9L);
        MigrationRun second = running(2L, 10L);
        when(runRepository.findByStatus(MigrationRunStatus.RUNNING))
                .thenReturn(List.of(first, second));
        when(taskRepository.findById(9L)).thenReturn(Optional.empty());
        when(taskRepository.findById(10L)).thenReturn(Optional.empty());

        int recovered = recovery.recoverInterrupted();

        assertThat(recovered).isEqualTo(2);
        verify(runRepository).save(first);
        verify(runRepository).save(second);
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
}
