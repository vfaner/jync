package com.qqmu.jync.service.etl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.repository.EtlTaskRepository;

/**
 * What a cron trigger does when the run cannot start.
 *
 * <p>The scheduler thread only logs whatever the trigger throws, so a task that is still running
 * or a full executor queue would drop a scheduled run with nothing but a stack trace in the
 * server log. The trigger swallows those two outcomes deliberately; this pins that they do not
 * escape into the scheduler.
 */
class EtlSchedulerTest {

    private final EtlTaskRepository repository = mock(EtlTaskRepository.class);
    private final EtlTaskService taskService = mock(EtlTaskService.class);
    private EtlScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EtlScheduler(repository, taskService);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void aTriggerWhileTheTaskIsAlreadyRunningDoesNotEscape() {
        when(taskService.submit(9L)).thenReturn(false);

        scheduler.trigger(9L);

        verify(taskService).submit(9L);
    }

    @Test
    void aTriggerWithAFullQueueDoesNotEscape() {
        when(taskService.submit(9L)).thenThrow(new IllegalStateException("error.etl.queue.full"));

        scheduler.trigger(9L);

        verify(taskService).submit(9L);
    }
}
