package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * P2: shutdown must wait for in-flight cycles but only up to the configured bound; a stuck
 * cycle can no longer hang process exit.
 */
class BoundedQuartzShutdownTest {

    private final Scheduler scheduler = mock(Scheduler.class);

    private ContextClosedEvent closedEvent() {
        return new ContextClosedEvent(new StaticApplicationContext());
    }

    @Test
    void waitsUntilActiveJobsDrainThenReturns() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(scheduler.getCurrentlyExecutingJobs()).thenAnswer(inv -> {
            if (polls.getAndIncrement() < 2) {
                return List.of(mock(JobExecutionContext.class));
            }
            return List.of();
        });
        SyncProperties properties = new SyncProperties();
        properties.setShutdownWaitMs(5000);

        long start = System.currentTimeMillis();
        new BoundedQuartzShutdown(scheduler, properties).onContextClosed(closedEvent());

        assertThat(System.currentTimeMillis() - start).isLessThan(1500);
        verify(scheduler).standby();
    }

    @Test
    void aStuckJobIsAbandonedAfterTheBudget() throws Exception {
        when(scheduler.getCurrentlyExecutingJobs())
                .thenReturn(List.of(mock(JobExecutionContext.class)));
        SyncProperties properties = new SyncProperties();
        properties.setShutdownWaitMs(300);

        long start = System.currentTimeMillis();
        new BoundedQuartzShutdown(scheduler, properties).onContextClosed(closedEvent());

        // 300ms bound, not the indefinite wait the old waitForJobsToComplete=true gave.
        assertThat(System.currentTimeMillis() - start).isLessThan(1500);
        verify(scheduler).standby();
    }

    @Test
    void aZeroBudgetSkipsTheWaitEntirely() throws Exception {
        SyncProperties properties = new SyncProperties();
        properties.setShutdownWaitMs(0);

        new BoundedQuartzShutdown(scheduler, properties).onContextClosed(closedEvent());

        verify(scheduler, never()).standby();
        verify(scheduler, never()).getCurrentlyExecutingJobs();
    }
}
