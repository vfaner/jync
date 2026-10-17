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
 * "Last sync" must mean "when data last landed", not "when the cycle last ran".
 *
 * <p>The polling engine fires once a second even when the source has nothing new, and
 * {@code recordOutcome} used to stamp {@code lastSyncTime} on every round. The dashboard then
 * showed a timestamp ticking forward while the row counter stood still — the two numbers
 * contradicted each other, and an idle project looked perpetually busy.
 */
class SyncTaskStoreLastSyncTimeTest {

    /** Far enough in the past that any refresh is unmistakable. */
    private static final Instant LAST_REAL_SYNC = Instant.parse("2020-01-01T00:00:00Z");

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
        task.setLastSyncTime(LAST_REAL_SYNC);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(task));
    }

    @Test
    @DisplayName("an idle cycle leaves 上次同步 where the last real change put it")
    void anIdleCycleDoesNotMoveTheLastSyncTime() {
        store.recordOutcome(1L, new SyncResult());

        assertThat(task.getLastSyncTime()).isEqualTo(LAST_REAL_SYNC);
        // Only the timestamp is held back: what the latest cycle found is still worth showing,
        // otherwise the list would keep claiming the last change's summary forever.
        assertThat(task.getLastSyncResult()).isEqualTo("No changes");
    }

    @Test
    @DisplayName("a cycle that landed rows moves it")
    void aCycleThatChangedRowsMovesTheLastSyncTime() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(1);

        store.recordOutcome(1L, result);

        assertThat(task.getLastSyncTime()).isAfter(LAST_REAL_SYNC);
    }

    @Test
    @DisplayName("a structure-only cycle counts as a change")
    void aStructureChangeMovesTheLastSyncTime() {
        SyncResult result = new SyncResult();
        result.setStructureChanges(1);

        store.recordOutcome(1L, result);

        assertThat(task.getLastSyncTime()).isAfter(LAST_REAL_SYNC);
    }

    @Test
    @DisplayName("a failed cycle changes nothing, so it must not claim a sync")
    void aFailedCycleDoesNotMoveTheLastSyncTime() {
        SyncResult result = new SyncResult();
        result.addError("connection refused");

        store.recordOutcome(1L, result);

        assertThat(task.getLastSyncTime()).isEqualTo(LAST_REAL_SYNC);
        assertThat(task.getLastSyncResult()).startsWith("FAILED:");
    }

    @Test
    @DisplayName("a CDC window that applied rows moves it, same as a polling cycle")
    void aCdcWindowThatAppliedRowsMovesTheLastSyncTime() {
        store.markCdcDataApplied(1L);

        assertThat(task.getLastSyncTime()).isAfter(LAST_REAL_SYNC);
    }
}
