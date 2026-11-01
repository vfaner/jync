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
 * The success heartbeat behind the delay card.
 *
 * <p>{@code lastSyncTime} answers "when did data last land" and deliberately stands still on
 * an idle source; the delay card needs the other question — "when did the engine last finish
 * a cycle" — or a healthy project polling every second shows a delay measured in hours.
 */
class SyncTaskStoreLastSuccessAtTest {

    /** Far enough in the past that any refresh is unmistakable. */
    private static final Instant LAST_SUCCESS = Instant.parse("2020-01-01T00:00:00Z");

    private SyncTask task;
    private SyncTaskStore store;

    @BeforeEach
    void setUp() {
        SyncTaskRepository repository = mock(SyncTaskRepository.class);
        store = new SyncTaskStore(repository, new SyncProperties());
        task = new SyncTask();
        task.setProjectId(1L);
        task.setStatus(TaskStatus.RUNNING);
        task.setConsecutiveFailures(0);
        task.setLastSuccessAt(LAST_SUCCESS);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(task));
    }

    @Test
    @DisplayName("an idle cycle that finished cleanly still is a heartbeat")
    void anIdleSuccessfulCycleStampsTheHeartbeat() {
        store.recordOutcome(1L, new SyncResult());

        assertThat(task.getLastSuccessAt()).isAfter(LAST_SUCCESS);
    }

    @Test
    @DisplayName("a failed cycle does not claim the engine is healthy")
    void aFailedCycleDoesNotStampTheHeartbeat() {
        SyncResult result = new SyncResult();
        result.addError("connection refused");

        store.recordOutcome(1L, result);

        assertThat(task.getLastSuccessAt()).isEqualTo(LAST_SUCCESS);
    }

    @Test
    @DisplayName("a partially failed cycle is not a clean pass either")
    void aPartiallyFailedCycleDoesNotStampTheHeartbeat() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(500);
        result.addError("table B: deletion pass failed");

        store.recordOutcome(1L, result);

        assertThat(task.getLastSuccessAt()).isEqualTo(LAST_SUCCESS);
    }
}
