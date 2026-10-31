package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.ConnectionCatalogService;

/**
 * Running a batch means running its members one after another on a single connection pair. A
 * member that is busy or fails must not stop the tables behind it, and the batch always leaves a
 * summary that says what happened.
 */
class EtlBatchRunTest {

    private final EtlBatchRepository batchRepository = mock(EtlBatchRepository.class);
    private final EtlTaskRepository taskRepository = mock(EtlTaskRepository.class);
    private final DatabaseConfigService databaseConfigService = mock(DatabaseConfigService.class);
    private final EtlTaskService taskService = mock(EtlTaskService.class);
    private final EtlCheckpointStore checkpointStore = mock(EtlCheckpointStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final EtlExecutionGuard guard = new EtlExecutionGuard();
    private final ConnectionCatalogService catalogService = mock(ConnectionCatalogService.class);
    private final SqlDialectFactory dialectFactory = mock(SqlDialectFactory.class);
    private final Executor executor = mock(Executor.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private EtlBatchService service;

    @BeforeEach
    void setUp() {
        service = new EtlBatchService(batchRepository, taskRepository, databaseConfigService,
                taskService, checkpointStore, dataSourceManager, guard, catalogService,
                dialectFactory, executor, events);
    }

    @Test
    void runsMembersInIdOrderSharingOneConnectionProvider() {
        stubBatchAndMembers(List.of(member(1L), member(2L), member(3L)));
        when(taskService.executeWithin(anyLong(), any())).thenReturn(EtlRunResult.success(1, 1));

        service.execute(5L);

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<EtlConnectionProvider> providers =
                ArgumentCaptor.forClass(EtlConnectionProvider.class);
        verify(taskService, times(3)).executeWithin(ids.capture(), providers.capture());
        assertThat(ids.getAllValues()).containsExactly(1L, 2L, 3L);
        EtlConnectionProvider first = providers.getAllValues().get(0);
        assertThat(providers.getAllValues()).allMatch(provider -> provider == first);
    }

    @Test
    void oneMemberFailureDoesNotStopTheRest() {
        stubBatchAndMembers(List.of(member(1L), member(2L), member(3L)));
        when(taskService.executeWithin(eq(1L), any())).thenThrow(new IllegalStateException("boom"));
        when(taskService.executeWithin(eq(2L), any())).thenReturn(EtlRunResult.success(2, 2));
        when(taskService.executeWithin(eq(3L), any())).thenReturn(EtlRunResult.success(3, 3));

        service.execute(5L);

        verify(taskService).recordFailure(1L, "boom");
        verify(taskService).executeWithin(eq(2L), any());
        verify(taskService).executeWithin(eq(3L), any());
    }

    @Test
    void aBusyMemberIsSkippedAndCounted() {
        stubBatchAndMembers(List.of(member(1L), member(2L), member(3L)));
        when(taskService.executeWithin(anyLong(), any())).thenReturn(EtlRunResult.success(1, 1));
        guard.tryAcquire(2L);

        service.execute(5L);

        verify(taskService, never()).executeWithin(eq(2L), any());
        ArgumentCaptor<EtlBatch> saved = ArgumentCaptor.forClass(EtlBatch.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getLastRunSuccess()).isFalse();
        assertThat(saved.getValue().getLastRunResult()).contains("skipped=1");
    }

    @Test
    void aCleanRunWritesASuccessSummary() {
        stubBatchAndMembers(List.of(member(1L), member(2L)));
        when(taskService.executeWithin(anyLong(), any())).thenReturn(EtlRunResult.success(4, 3));

        service.execute(5L);

        ArgumentCaptor<EtlBatch> saved = ArgumentCaptor.forClass(EtlBatch.class);
        verify(batchRepository).save(saved.capture());
        EtlBatch batch = saved.getValue();
        assertThat(batch.getLastRunSuccess()).isTrue();
        assertThat(batch.getLastRunTime()).isNotNull();
        assertThat(batch.getLastRunResult()).isEqualTo("SUCCESS members=2 read=8 written=6");
    }

    @Test
    void aRunWithFailuresWritesAPartialSummary() {
        stubBatchAndMembers(List.of(member(1L), member(2L), member(3L)));
        when(taskService.executeWithin(eq(1L), any())).thenReturn(EtlRunResult.success(5, 4));
        when(taskService.executeWithin(eq(2L), any())).thenThrow(new IllegalStateException("boom"));
        guard.tryAcquire(3L);

        service.execute(5L);

        ArgumentCaptor<EtlBatch> saved = ArgumentCaptor.forClass(EtlBatch.class);
        verify(batchRepository).save(saved.capture());
        EtlBatch batch = saved.getValue();
        assertThat(batch.getLastRunSuccess()).isFalse();
        assertThat(batch.getLastRunResult())
                .isEqualTo("PARTIAL members=3 ok=1 failed=1 skipped=1 read=5 written=4");
    }

    /**
     * A deleted batch (a stale tab pressing Run) must not be described as "no tables yet";
     * the operator needs to know the batch itself is gone.
     */
    @Test
    void submitOfAMissingBatchReportsNotFound() {
        when(batchRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.batch.not.found");
        verifyNoInteractions(executor);
        assertThat(service.isRunning(99L)).isFalse();
    }

    @Test
    void submitRefusesAnEmptyBatchBeforeQueueing() {
        when(batchRepository.existsById(5L)).thenReturn(true);
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submit(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.empty");
        verifyNoInteractions(executor);
        assertThat(service.isRunning(5L)).isFalse();
    }

    @Test
    void submitRefusesWhenTheBatchGuardIsHeld() {
        when(batchRepository.existsById(5L)).thenReturn(true);
        guard.tryAcquire("batch:5");

        assertThat(service.submit(5L)).isFalse();
        verifyNoInteractions(executor);
    }

    @Test
    void queueFullReleasesTheBatchGuard() {
        when(batchRepository.existsById(5L)).thenReturn(true);
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of(member(1L)));
        doThrow(new RejectedExecutionException()).when(executor).execute(any());

        assertThatThrownBy(() -> service.submit(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.queue.full");
        assertThat(service.isRunning(5L)).isFalse();
    }

    @Test
    void submitRunsTheBatchAndReleasesTheGuard() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        stubBatchAndMembers(List.of(member(1L)));
        when(taskService.executeWithin(anyLong(), any())).thenReturn(EtlRunResult.success(1, 1));

        assertThat(service.submit(5L)).isTrue();

        verify(taskService).executeWithin(eq(1L), any());
        assertThat(service.isRunning(5L)).isFalse();
    }

    /**
     * A batch-level failure before the member loop (an unreadable member list, a dead pool) must
     * leave a failed summary. Logging only left the batch page showing the previous run's result
     * as if this trigger never happened.
     */
    @Test
    void aCrashOutsideTheMemberLoopLeavesAFailedSummary() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        stubBatchAndMembers(List.of(member(1L)));
        when(batchRepository.existsById(5L)).thenReturn(true);
        // First call is submit's "is there anything to run" check; the second is execute()'s.
        when(taskRepository.findByBatchIdOrderByIdAsc(5L))
                .thenReturn(List.of(member(1L)))
                .thenThrow(new IllegalStateException("metadata unavailable"));

        assertThat(service.submit(5L)).isTrue();

        ArgumentCaptor<EtlBatch> saved = ArgumentCaptor.forClass(EtlBatch.class);
        verify(batchRepository).save(saved.capture());
        assertThat(saved.getValue().getLastRunSuccess()).isFalse();
        assertThat(saved.getValue().getLastRunResult())
                .startsWith("FAILED").contains("metadata unavailable");
        assertThat(saved.getValue().getLastRunTime()).isNotNull();
        assertThat(service.isRunning(5L)).isFalse();
    }

    private void stubBatchAndMembers(List<EtlTask> members) {
        when(batchRepository.existsById(5L)).thenReturn(true);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch(5L)));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(members);
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static EtlBatch batch(Long id) {
        EtlBatch batch = new EtlBatch();
        batch.setId(id);
        batch.setName("nightly");
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        return batch;
    }

    private static EtlTask member(Long id) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setBatchId(5L);
        return task;
    }
}
