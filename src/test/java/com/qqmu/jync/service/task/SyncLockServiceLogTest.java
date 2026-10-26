package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.cdc.LogCapture;

/**
 * A second instance holding the lease is the normal steady state of a multi-instance
 * deployment: the loser of each scheduled fire re-checks it every poll interval. Writing
 * that at INFO turns one idle project into tens of thousands of lines a day.
 */
class SyncLockServiceLogTest {

    @Test
    void aLockHeldByAnotherInstanceIsDebugNotInfo() {
        SyncLockStore store = mock(SyncLockStore.class);
        when(store.acquire(eq(9L), anyString(), anyLong())).thenReturn(false);
        SyncLockService service = new SyncLockService(store, new SyncProperties(), 8080);
        Logger logger = (Logger) LoggerFactory.getLogger(SyncLockService.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncLockService.class);
        try {
            assertThat(service.tryAcquire(9L)).isNull();
        } finally {
            LogCapture.detach(SyncLockService.class, logs);
            logger.setLevel(previous);
        }

        assertThat(LogCapture.messagesAt(Level.INFO, logs))
                .noneSatisfy(line -> assertThat(line).contains("locked by another instance"));
        assertThat(LogCapture.messagesAt(Level.DEBUG, logs))
                .anySatisfy(line -> assertThat(line).contains("locked by another instance"));
    }

    /**
     * A fire whose execution never returns freezes its Quartz trigger for good: the
     * schedule silently stops while the page keeps showing a past "next run". The watchdog
     * is the only component that runs for the whole lifetime of that execution, so it is
     * where the wedge has to be reported — once, not every renewal tick.
     */
    @Test
    void aCycleWithNoProgressWarnsOnceThatItIsWedged() throws InterruptedException {
        SyncLockStore store = mock(SyncLockStore.class);
        when(store.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(store.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        SyncProperties properties = new SyncProperties();
        properties.setLockTtlMs(600L);          // watchdog interval = max(1000, ttl/3)
        properties.setStuckThresholdMs(200L);   // exceeded before the first tick
        SyncLockService service = new SyncLockService(store, properties, 8080);

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncLockService.class);
        try (SyncLockService.LockHandle handle = service.tryAcquire(1L)) {
            assertThat(handle).isNotNull();
            Thread.sleep(2_500);   // two-plus watchdog ticks
        } finally {
            LogCapture.detach(SyncLockService.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .filteredOn(line -> line.contains("no progress"))
                .as("one warning per cycle, however many renewal ticks follow")
                .hasSize(1);
    }
}
