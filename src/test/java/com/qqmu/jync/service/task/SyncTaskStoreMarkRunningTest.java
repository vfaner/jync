package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.SyncTaskRepository;

/**
 * Starting a cycle says the round began — not that the project is healthy again.
 *
 * <p>{@code markRunning} used to overwrite every status with RUNNING, including ERROR. The
 * ERROR status is the B7 hat: a CDC project parked by a fatal failure must not be restarted
 * anywhere until a human rebuilds the baseline. A round that started and was then deferred
 * (pool at its limit) cleared the hat permanently, because a deferred round records nothing.
 */
class SyncTaskStoreMarkRunningTest {

    private SyncTaskRepository repository;
    private SyncTaskStore store;
    private SyncTask task;

    @BeforeEach
    void setUp() {
        repository = mock(SyncTaskRepository.class);
        store = new SyncTaskStore(repository, new SyncProperties());
        task = new SyncTask();
        task.setProjectId(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(task));
    }

    @Test
    void aStartedCycleDoesNotLiftTheErrorHat() {
        task.setStatus(TaskStatus.ERROR);
        task.setConsecutiveFailures(3);
        task.setLastSyncResult("SERVER_ID_CONFLICT");

        store.markRunning(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.ERROR);
        assertThat(task.getLastSyncResult()).isEqualTo("SERVER_ID_CONFLICT");
    }

    @Test
    void aStartedCycleMarksAStoppedTaskRunning() {
        task.setStatus(TaskStatus.STOPPED);

        store.markRunning(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void aFinishedManualRunReturnsTheTaskToStopped() {
        // markRunning flipped a stopped project to RUNNING for the duration of the manual
        // cycle; the status must go back once the round ends, or the page keeps saying
        // "同步中" for a project nobody asked to enable.
        task.setStatus(TaskStatus.RUNNING);

        store.markStoppedUnlessError(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.STOPPED);
    }

    @Test
    void aFinishedManualRunKeepsTheErrorHat() {
        task.setStatus(TaskStatus.ERROR);
        task.setLastSyncResult("CDC_FATAL");

        store.markStoppedUnlessError(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.ERROR);
        assertThat(task.getLastSyncResult()).isEqualTo("CDC_FATAL");
    }
}
