package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * A polling cycle whose fire never returns freezes its Quartz trigger: with
 * {@code @DisallowConcurrentExecution} the trigger is taken out of the time wheel for the
 * lifetime of that execution, so every later fire is skipped and the page keeps showing a
 * next-run time that has already passed.
 *
 * <p>To say that on the page, the lock service has to remember which polling cycle is
 * executing here, when it started, and when it last reported progress. Watchdog renewals
 * keep the lease alive but are not progress — a wedged thread would otherwise look healthy
 * for the full renewal cap.
 */
class SyncLockServiceActiveCycleTest {

    private SyncLockStore store;

    private SyncLockService serviceWithTtl(long ttlMs) {
        store = mock(SyncLockStore.class);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        SyncProperties properties = new SyncProperties();
        properties.setLockTtlMs(ttlMs);
        return new SyncLockService(store, properties, 8080);
    }

    @Test
    void aPollingCycleIsVisibleWhileItsHandleIsOpen() {
        SyncLockService service = serviceWithTtl(300_000L);

        try (SyncLockService.LockHandle handle = service.tryAcquire(1L)) {
            assertThat(handle).isNotNull();
            assertThat(service.activePollingCycle(1L)).isPresent()
                    .get()
                    .satisfies(cycle -> {
                        assertThat(cycle.projectId()).isEqualTo(1L);
                        assertThat(cycle.startedAt()).isNotNull();
                        assertThat(cycle.lastProgressAt()).isNotNull();
                    });
        }
    }

    @Test
    void closingTheHandleEndsTheCycleReport() {
        SyncLockService service = serviceWithTtl(300_000L);

        SyncLockService.LockHandle handle = service.tryAcquire(1L);
        assertThat(service.activePollingCycle(1L)).isPresent();
        handle.close();

        assertThat(service.activePollingCycle(1L)).isEmpty();
    }

    @Test
    void aDaemonLeaseIsNotReportedAsAPollingCycle() {
        SyncLockService service = serviceWithTtl(300_000L);

        try (SyncLockService.LockHandle handle = service.tryAcquireForDaemon(1L, 30_000L)) {
            assertThat(handle).isNotNull();
            // A CDC consumer renews every loop iteration forever; it is not a "fire" whose
            // return the schedule is waiting on, so it must not drive the page's stuck hint.
            assertThat(service.activePollingCycle(1L)).isEmpty();
        }
    }

    @Test
    void onlyAnExplicitRenewCountsAsProgress() {
        // TTL 3000ms -> the watchdog renews every second. Those renewals prove the JVM is
        // alive, not that the cycle moved; a wedged thread must not look fresh.
        SyncLockService service = serviceWithTtl(3_000L);

        try (SyncLockService.LockHandle handle = service.tryAcquire(1L)) {
            verify(store, timeout(3_000).atLeastOnce()).renew(eq(1L), anyString(), anyLong());
            Instant afterWatchdog = service.activePollingCycle(1L).orElseThrow().lastProgressAt();
            assertThat(afterWatchdog).isEqualTo(
                    service.activePollingCycle(1L).orElseThrow().startedAt());

            assertThat(handle.renew()).isTrue();
            assertThat(service.activePollingCycle(1L).orElseThrow().lastProgressAt())
                    .isAfter(afterWatchdog);
        }
    }
}
