package com.qqmu.jync.service.task;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;

/**
 * The RUNNING status left by a kill -9 is cleared once its lease is gone. The heal must keep
 * skipping a cycle that is genuinely in flight — here or on another node — otherwise the
 * periodic pass would stop live syncs.
 */
class SyncBootstrapPhantomStatusTest {

    private static SyncBootstrap bootstrap(SyncTaskStore store, SyncLockService locks) {
        return new SyncBootstrap(mock(ProjectRepository.class), mock(SyncScheduler.class),
                mock(CdcConsumerManager.class), store, locks);
    }

    private static SyncTask running(long projectId) {
        SyncTask task = new SyncTask();
        task.setProjectId(projectId);
        task.setStatus(TaskStatus.RUNNING);
        return task;
    }

    @Test
    void aLiveLeaseIsLeftAlone() {
        SyncTaskStore store = mock(SyncTaskStore.class);
        SyncLockService locks = mock(SyncLockService.class);
        when(store.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running(7L)));
        when(locks.isLeaseHeldByLiveOwner(7L)).thenReturn(true);

        bootstrap(store, locks).healStaleStatuses();

        verify(store, never()).markStoppedUnlessError(anyLong());
    }

    @Test
    void aTaskWhoseLeaseIsGoneIsReset() {
        SyncTaskStore store = mock(SyncTaskStore.class);
        SyncLockService locks = mock(SyncLockService.class);
        when(store.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running(7L)));
        when(locks.isLeaseHeldByLiveOwner(7L)).thenReturn(false);

        bootstrap(store, locks).healStaleStatuses();

        verify(store).markStoppedUnlessError(7L);
    }
}
