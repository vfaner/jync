package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;

/**
 * What a cron trigger does when the run cannot start, and which rows are allowed to register a
 * trigger at all.
 *
 * <p>The scheduler thread only logs whatever the trigger throws, so a task that is still running
 * or a full executor queue would drop a scheduled run with nothing but a stack trace in the
 * server log. The trigger swallows those two outcomes deliberately; this pins that they do not
 * escape into the scheduler.
 */
class EtlSchedulerTest {

    private static final String CRON = "0 0 3 * * *";

    private final EtlTaskRepository repository = mock(EtlTaskRepository.class);
    private final EtlTaskService taskService = mock(EtlTaskService.class);
    private final EtlBatchRepository batchRepository = mock(EtlBatchRepository.class);
    private final EtlBatchService batchService = mock(EtlBatchService.class);
    private EtlScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EtlScheduler(repository, taskService, batchRepository, batchService);
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

    @Test
    void aTriggeredBatchSubmitsIt() {
        when(batchService.submit(5L)).thenReturn(true);

        scheduler.triggerBatch(5L);

        verify(batchService).submit(5L);
    }

    @Test
    void aTriggeredBatchSwallowsSubmitFailures() {
        when(batchService.submit(5L)).thenThrow(new IllegalStateException("error.etl.queue.full"));

        scheduler.triggerBatch(5L);

        verify(batchService).submit(5L);
    }

    /** A member's schedule belongs to its batch; registering it here would run it twice. */
    @Test
    void aMemberTaskIsNeverScheduled() {
        EtlTask member = task(9L);
        member.setBatchId(5L);

        assertThat(EtlScheduler.shouldSchedule(member)).isFalse();
    }

    @Test
    void aStandaloneEnabledTaskWithACronIsScheduled() {
        assertThat(EtlScheduler.shouldSchedule(task(9L))).isTrue();
    }

    @Test
    void aBatchWithoutACronIsNotScheduled() {
        EtlBatch batch = batch(5L);
        batch.setCronExpression(null);

        assertThat(EtlScheduler.shouldSchedule(batch)).isFalse();
    }

    @Test
    void anEnabledBatchWithACronIsScheduled() {
        assertThat(EtlScheduler.shouldSchedule(batch(5L))).isTrue();
    }

    private static EtlTask task(Long id) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setEnabled(true);
        task.setCronExpression(CRON);
        return task;
    }

    private static EtlBatch batch(Long id) {
        EtlBatch batch = new EtlBatch();
        batch.setId(id);
        batch.setEnabled(true);
        batch.setCronExpression(CRON);
        return batch;
    }
}
