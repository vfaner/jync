package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * {@code LockHandle.renew} is the heartbeat a long cycle sends between tables. Its return
 * value is what lets the engine tell "lease extended" from "lease lost" — a renew that only
 * logged a warning would let a cycle keep writing after another instance took the lock over.
 *
 * <p>Each held lease is additionally renewed on a timer (the watchdog), because a single
 * batch longer than the TTL would otherwise sit unprotected between commit-boundary renews.
 */
class SyncLockServiceRenewTest {

    private SyncLockStore store;

    private SyncLockService serviceWithTtl(long ttlMs) {
        store = mock(SyncLockStore.class);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        SyncProperties properties = new SyncProperties();
        properties.setLockTtlMs(ttlMs);
        return new SyncLockService(store, properties, 8080);
    }

    @Test
    void renewReportsAHealthyLeaseAsTrue() {
        SyncLockService service = serviceWithTtl(300_000L);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle).isNotNull();
        assertThat(handle.renew()).isTrue();
        handle.close();
    }

    @Test
    void renewReportsALostLeaseAsFalse() {
        SyncLockService service = serviceWithTtl(300_000L);
        // The row expired and a peer reclaimed it: the store refuses the renew.
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(false);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle.renew()).isFalse();
        handle.close();
    }

    @Test
    void twoInstancesOnTheSamePortStillGetDistinctOwnerIds() {
        SyncLockService service = serviceWithTtl(300_000L);
        // Two nodes that resolve to the same hostname and run on the same port (cloned VM /
        // container template) must not share an owner id, or each could act on the other's locks.
        SyncLockService other = new SyncLockService(mock(SyncLockStore.class),
                new SyncProperties(), 8080);

        assertThat(service.getOwnerId()).isNotEqualTo(other.getOwnerId());
        // The shared, still-readable host:port prefix remains; only the suffix disambiguates.
        String prefix = service.getOwnerId()
                .substring(0, service.getOwnerId().lastIndexOf(':') + 1);
        assertThat(other.getOwnerId()).startsWith(prefix);
    }

    @Test
    void renewSurvivesAStoreFailure() {
        SyncLockService service = serviceWithTtl(300_000L);
        // An unreachable lock store means the lease cannot be proven alive; the safe answer
        // is "lost", reported rather than thrown so the engine can abort cleanly.
        when(store.renew(eq(1L), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("store unreachable"));

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(handle.renew()).isFalse();
        handle.close();
    }

    @Test
    void theWatchdogRenewsAHeldLeaseOnATimer() {
        // TTL 3000ms -> watchdog fires at the 1000ms interval. Renewal between commits
        // would leave a 3s+ batch exposed; the timer must renew while the handle is open.
        SyncLockService service = serviceWithTtl(3_000L);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        verify(store, timeout(3_000).atLeastOnce()).renew(eq(1L), anyString(), anyLong());
        handle.close();
    }

    @Test
    void closingTheHandleStopsTheWatchdog() throws InterruptedException {
        SyncLockService service = serviceWithTtl(3_000L);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        // Let at least one timer tick land...
        verify(store, timeout(2_500).atLeastOnce()).renew(eq(1L), anyString(), anyLong());
        int callsBefore = countRenewCalls();
        handle.close();
        // ...then no further renewal: a late tick would race a new owner on the project.
        Thread.sleep(2_000);
        assertThat(countRenewCalls()).isEqualTo(callsBefore);
    }

    @Test
    void aLostLeaseStopsTheWatchdog() {
        SyncLockService service = serviceWithTtl(3_000L);
        // Every renewal says the lease is gone; after the first failure the timer must
        // cancel rather than keep hammering the store.
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(false);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        verify(store, timeout(3_000).atLeastOnce()).renew(eq(1L), anyString(), anyLong());
        int callsBefore = countRenewCalls();
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(countRenewCalls()).isEqualTo(callsBefore);
        handle.close();
    }

    private int countRenewCalls() {
        return org.mockito.Mockito.mockingDetails(store).getInvocations().stream()
                .filter(invocation -> "renew".equals(invocation.getMethod().getName()))
                .mapToInt(invocation -> 1).sum();
    }
}
