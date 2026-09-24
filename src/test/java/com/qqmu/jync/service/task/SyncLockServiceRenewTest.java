package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * {@code LockHandle.renew} is the heartbeat a long cycle sends between tables. Its return
 * value is what lets the engine tell "lease extended" from "lease lost" — a renew that only
 * logged a warning would let a cycle keep writing after another instance took the lock over.
 */
class SyncLockServiceRenewTest {

    private SyncLockStore store;
    private SyncLockService service;

    @BeforeEach
    void setUp() {
        store = mock(SyncLockStore.class);
        service = new SyncLockService(store, new SyncProperties(), 8080);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void renewReportsAHealthyLeaseAsTrue() {
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle).isNotNull();
        assertThat(handle.renew()).isTrue();
        handle.close();
    }

    @Test
    void renewReportsALostLeaseAsFalse() {
        // The row expired and a peer reclaimed it: the store refuses the renew.
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(false);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle.renew()).isFalse();
        handle.close();
    }

    @Test
    void renewSurvivesAStoreFailure() {
        // An unreachable lock store means the lease cannot be proven alive; the safe answer
        // is "lost", reported rather than thrown so the engine can abort cleanly.
        when(store.renew(eq(1L), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("store unreachable"));

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle.renew()).isFalse();
        handle.close();
    }
}
