package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * 调用方驱动的续租节流:每个源事务一次续租会把元数据库写成热点(与位点节流同因)。
 * 窗口内复用上次判定,看门狗仍以 TTL/3 兜底探测归属。
 */
class SyncLockServiceTest {

    @Test
    void rapidRenewalsReuseTheLastVerdictWithinTheThrottleWindow() {
        SyncLockStore store = mock(SyncLockStore.class);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        SyncLockService service = new SyncLockService(store, new SyncProperties(), 8080);

        var handle = service.tryAcquireForDaemon(1L, 30_000L);
        assertThat(handle).isNotNull();
        try {
            assertThat(handle.renew()).isTrue();
            assertThat(handle.renew()).isTrue();
            assertThat(handle.renew()).isTrue();
            // 三次快速续租只写一次 store,其余复用缓存判定。
            verify(store, times(1)).renew(eq(1L), anyString(), anyLong());
        } finally {
            handle.close();
        }
    }

    @Test
    void aLostLeaseStopsRenewalsImmediately() throws InterruptedException {
        SyncLockStore store = mock(SyncLockStore.class);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true, false);
        SyncLockService service = new SyncLockService(store, new SyncProperties(), 8080);

        var handle = service.tryAcquireForDaemon(1L, 30_000L);
        assertThat(handle).isNotNull();
        try {
            assertThat(handle.renew()).isTrue();    // 首次真实续租,缓存 true
            Thread.sleep(1_100);                    // 跨过节流窗口
            assertThat(handle.renew()).isFalse();   // 真实续租,store 返回 false → 丢租约
            assertThat(handle.renew()).isFalse();   // 缓存 false,立即停写,不再打 store
            verify(store, times(2)).renew(eq(1L), anyString(), anyLong());
        } finally {
            handle.close();
        }
    }
}
