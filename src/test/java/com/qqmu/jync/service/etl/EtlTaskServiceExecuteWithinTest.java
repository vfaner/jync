package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * The batch runner needs a task execution entry point that does not open its own connection
 * pool. {@code executeWithin} is that seam: the caller owns the connections, and the run is
 * still recorded exactly like a standalone run.
 */
@ExtendWith(MockitoExtension.class)
class EtlTaskServiceExecuteWithinTest {

    @Mock private EtlTaskRepository taskRepository;
    @Mock private EtlRunRepository runRepository;
    @Mock private DatabaseConfigService databaseConfigService;
    @Mock private EtlRunner runner;
    @Mock private EtlCheckpointStore checkpointStore;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ApplicationEventPublisher events;

    private EtlTaskService service;

    @BeforeEach
    void setUp() {
        service = new EtlTaskService(taskRepository, runRepository, databaseConfigService, runner,
                checkpointStore, dataSourceManager, new EtlExecutionGuard(), Runnable::run, events);
    }

    @Test
    void executeWithinRunsOnTheProvidedConnectionsAndRecordsTheRun() {
        EtlTask task = task(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections();
        EtlConnectionProvider connections = mock(EtlConnectionProvider.class);
        when(runner.run(eq(task), any(), any(), same(connections)))
                .thenReturn(EtlRunResult.success(2, 2));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlRunResult result = service.executeWithin(7L, connections);

        assertThat(result.success()).isTrue();
        assertThat(task.getLastRunSuccess()).isTrue();
        assertThat(task.getLastRunResult()).contains("SUCCESS").contains("written=2");
        verify(runRepository).save(any());
    }

    /**
     * A run can take minutes. Editing the task meanwhile used to conflict at the final
     * {@code save} (the entity was loaded before the run and never re-read), so a run that
     * copied every row was recorded as a crash instead of a success. Recording must apply the
     * outcome to the row as it stands now, not to the snapshot from before the copy.
     */
    @Test
    void theOutcomeIsRecordedOnTheTaskAsItStandsAfterTheRun() {
        EtlTask loadedAtStart = task(7L);
        EtlTask editedDuringTheRun = task(7L);
        editedDuringTheRun.setName("renamed while running");
        when(taskRepository.findById(7L))
                .thenReturn(Optional.of(loadedAtStart), Optional.of(editedDuringTheRun));
        stubConnections();
        EtlConnectionProvider connections = mock(EtlConnectionProvider.class);
        when(runner.run(eq(loadedAtStart), any(), any(), same(connections)))
                .thenReturn(EtlRunResult.success(2, 2));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.executeWithin(7L, connections);

        verify(taskRepository).save(same(editedDuringTheRun));
        assertThat(editedDuringTheRun.getLastRunSuccess()).isTrue();
        assertThat(loadedAtStart.getLastRunSuccess()).isNull();
    }

    @Test
    void executeWithinTurnsAConfigurationFailureIntoAProgramFailure() {
        EtlTask task = task(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections();
        EtlConnectionProvider connections = mock(EtlConnectionProvider.class);
        when(runner.run(eq(task), any(), any(), same(connections)))
                .thenThrow(new IllegalArgumentException("bad task"));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlRunResult result = service.executeWithin(7L, connections);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(task.getLastRunResult()).contains("bad task");
    }

    @Test
    void executeStillOpensConnectionsOfItsOwn() {
        EtlTask task = task(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections();
        when(runner.run(eq(task), any(), any(), any())).thenReturn(EtlRunResult.success(1, 1));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(7L);

        ArgumentCaptor<EtlConnectionProvider> captor =
                ArgumentCaptor.forClass(EtlConnectionProvider.class);
        verify(runner).run(eq(task), any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(EtlConnections.class);
    }

    @Test
    void recordFailureLeavesAFailedRunBehind() {
        EtlTask task = task(7L);
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordFailure(7L, "boom");

        assertThat(task.getLastRunSuccess()).isFalse();
        assertThat(task.getLastRunResult()).contains("FAILED").contains("boom");
        verify(runRepository).save(any());
    }

    private void stubConnections() {
        DatabaseConfig source = config(1L);
        DatabaseConfig target = config(2L);
        when(databaseConfigService.findById(1L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(target));
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    private static EtlTask task(Long id) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName("nightly orders");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("SELECT id FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }
}
