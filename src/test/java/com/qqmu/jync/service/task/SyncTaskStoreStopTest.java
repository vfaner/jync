package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.SyncTaskRepository;

/**
 * Stopping a project while a cycle is in flight is a race by design: the runner finishes
 * its cycle and reports an outcome AFTER the stop was recorded. These tests pin who wins
 * — the stop must, or the UI shows a stopped project as running and the lease of the
 * still-writing runner gets dropped for a second instance to grab.
 */
class SyncTaskStoreStopTest {

    private SyncTaskRepository repository;
    private SyncTaskStore store;
    private SyncTask task;

    @BeforeEach
    void setUp() {
        repository = mock(SyncTaskRepository.class);
        store = new SyncTaskStore(repository, new SyncProperties());
        task = new SyncTask();
        task.setProjectId(1L);
        task.setStatus(TaskStatus.RUNNING);
        task.setConsecutiveFailures(0);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(task));
    }

    private SyncResult success() {
        return new SyncResult();
    }

    private SyncResult failure() {
        SyncResult result = new SyncResult();
        result.addError("connection refused");
        return result;
    }

    @Test
    @DisplayName("a success reported after the stop does not resurrect RUNNING")
    void successAfterStopKeepsStoppedStatus() {
        store.markStopped(1L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.STOPPED);

        store.recordOutcome(1L, success());

        assertThat(task.getStatus()).isEqualTo(TaskStatus.STOPPED);
        // The stats are still worth recording — the cycle really did run.
        assertThat(task.getLastSyncTime()).isNotNull();
        assertThat(task.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("a failure reported after the stop does not flip the project to ERROR")
    void failureAfterStopKeepsStoppedStatus() {
        store.markStopped(1L);

        store.recordOutcome(1L, failure());

        // ERROR promises "keeps retrying on schedule"; a stopped project has no schedule.
        assertThat(task.getStatus()).isEqualTo(TaskStatus.STOPPED);
        assertThat(task.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("an outcome on a live project still reports RUNNING as before")
    void outcomeOnRunningProjectStillReportsRunning() {
        store.recordOutcome(1L, success());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);

        task.setStatus(TaskStatus.ERROR);
        store.recordOutcome(1L, success());
        // A success after maxed-out failures clears the error state.
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    @DisplayName("stopping keeps the in-flight cycle's lease until the runner releases it")
    void markStoppedKeepsTheLease() {
        task.setLockOwner("testhost:8080");
        task.setLockExpiresAt(Instant.now().plusSeconds(300));

        store.markStopped(1L);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.STOPPED);
        // Clearing the lease here would let another instance acquire the lock and run
        // concurrently with the cycle that is still writing.
        assertThat(task.getLockOwner()).isEqualTo("testhost:8080");
        assertThat(task.getLockExpiresAt()).isNotNull();
    }
}
