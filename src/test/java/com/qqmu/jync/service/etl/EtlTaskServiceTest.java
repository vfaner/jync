package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlRun;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

@ExtendWith(MockitoExtension.class)
class EtlTaskServiceTest {

    @Mock
    private EtlTaskRepository taskRepository;
    @Mock
    private EtlRunRepository runRepository;
    @Mock
    private DatabaseConfigService databaseConfigService;
    @Mock
    private EtlRunner runner;
    @Mock
    private EtlCheckpointStore checkpointStore;
    @Mock
    private DataSourceManager dataSourceManager;
    @Mock
    private ApplicationEventPublisher events;

    private EtlExecutionGuard guard;
    private EtlTaskService service;

    @BeforeEach
    void setUp() {
        guard = new EtlExecutionGuard();
        service = new EtlTaskService(taskRepository, runRepository, databaseConfigService, runner,
                checkpointStore, dataSourceManager, guard, Runnable::run, events);
    }

    @Test
    void saveRejectsTheSameConnectionOnBothSides() {
        stubConnections(1L, 1L);

        assertThatThrownBy(() -> service.save(form(1L, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.same.database");
        verify(taskRepository, never()).save(any());
    }

    /**
     * MongoDB's SQL interface is read-only, so a task pointing at it as the target would be
     * accepted here and fail on its first write, after the operator had already waited for a
     * run. The refusal belongs at save time, where the operator can still change the target.
     */
    @Test
    void saveRejectsAReadOnlySqlTarget() {
        DatabaseConfig source = config(1L);
        DatabaseConfig target = config(2L);
        target.setType(DatabaseType.MONGODB);
        when(databaseConfigService.findById(1L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.save(form(1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.target.readOnly");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void saveRejectsARangeOverwriteWithoutACondition() {
        stubConnections(1L, 2L);
        EtlTask task = form(1L, 2L);
        task.setWriteMode(EtlWriteMode.RANGE_OVERWRITE);

        assertThatThrownBy(() -> service.save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.range.required");
    }

    @Test
    void saveRejectsAnInvalidCron() {
        stubConnections(1L, 2L);
        EtlTask task = form(1L, 2L);
        task.setCronExpression("every minute");

        assertThatThrownBy(() -> service.save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.cron.invalid");
    }

    @Test
    void saveRejectsAResumeKeyThatIsNotAPlainColumnName() {
        stubConnections(1L, 2L);
        EtlTask task = form(1L, 2L);
        task.setResumeKeyColumn("id; DROP TABLE dst");

        assertThatThrownBy(() -> service.save(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.resumeKey.invalid");
    }

    @Test
    void editingTheExtractionConfigurationClearsTheCheckpoint() {
        stubConnections(1L, 2L);
        EtlTask existing = existing(5L, "SELECT id FROM src");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask edit = form(1L, 2L);
        edit.setId(5L);
        edit.setSourceSql("SELECT id, name FROM src");
        service.save(edit);

        verify(checkpointStore).clear(5L);
    }

    @Test
    void renamingATaskKeepsTheCheckpoint() {
        stubConnections(1L, 2L);
        EtlTask existing = existing(5L, "SELECT id FROM src");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask edit = form(1L, 2L);
        edit.setId(5L);
        edit.setSourceSql("SELECT id FROM src");
        edit.setName("renamed");
        service.save(edit);

        verify(checkpointStore, never()).clear(eq(5L));
    }

    @Test
    void submitRefusesATaskThatIsAlreadyRunning() {
        guard.tryAcquire(9L);

        assertThat(service.submit(9L)).isFalse();
        guard.release(9L);
    }

    /**
     * A saturated queue must not leave the guard held — that would make the task look stuck as
     * running forever — and must reach the operator as a message, not a 500 page.
     */
    @Test
    void aRejectedSubmissionReleasesTheGuardAndReportsAFullQueue() {
        EtlTaskService saturated = new EtlTaskService(taskRepository, runRepository,
                databaseConfigService, runner, checkpointStore, dataSourceManager, guard, task -> {
                    throw new RejectedExecutionException("queue full");
                }, events);

        assertThatThrownBy(() -> saturated.submit(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.queue.full");
        assertThat(guard.isRunning(9L)).isFalse();
    }

    @Test
    void executeRecordsASuccessfulRun() {
        EtlTask task = existing(7L, "SELECT id FROM src");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections(1L, 2L);
        when(runner.run(eq(task), any(), any(), any())).thenReturn(EtlRunResult.success(3, 3));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlRunResult result = service.execute(7L);

        assertThat(result.success()).isTrue();
        assertThat(task.getLastRunSuccess()).isTrue();
        assertThat(task.getLastRunResult()).contains("SUCCESS").contains("written=3");
        assertThat(task.getLastRunTime()).isNotNull();
    }

    /**
     * The dashboard's ETL line is drawn from this history, not from the change log: a run writes
     * one row per execution with the rows it put into the target, so the chart can count the
     * runs and sum their volume without guessing which change-log rows were ETL.
     */
    @Test
    void executeKeepsOneHistoryRowForTheTrendChart() {
        EtlTask task = existing(7L, "SELECT id FROM src");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections(1L, 2L);
        when(runner.run(eq(task), any(), any(), any())).thenReturn(EtlRunResult.success(9, 3));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(7L);

        ArgumentCaptor<EtlRun> captor = ArgumentCaptor.forClass(EtlRun.class);
        verify(runRepository).save(captor.capture());
        EtlRun run = captor.getValue();
        assertThat(run.getEtlTaskId()).isEqualTo(7L);
        assertThat(run.getSuccess()).isTrue();
        assertThat(run.getAffectedRows()).isEqualTo(3);
        assertThat(run.getOccurredAt()).isNotNull();
    }

    /** A failed run is activity too — the chart must show the attempt, not a quiet hour. */
    @Test
    void aFailedRunIsRecordedAsWell() {
        EtlTask task = existing(7L, "SELECT id FROM src");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections(1L, 2L);
        when(runner.run(eq(task), any(), any(), any()))
                .thenReturn(EtlRunResult.failure(0, 0, EtlRunResult.Side.TARGET, "denied"));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(7L);

        ArgumentCaptor<EtlRun> captor = ArgumentCaptor.forClass(EtlRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getSuccess()).isFalse();
        assertThat(captor.getValue().getAffectedRows()).isZero();
    }

    @Test
    void executeRecordsAConfigurationFailureWithoutCallingTheRunner() {
        EtlTask task = existing(7L, "SELECT id FROM src");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(databaseConfigService.findById(1L)).thenReturn(Optional.empty());
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlRunResult result = service.execute(7L);

        assertThat(result.success()).isFalse();
        assertThat(result.failureSide()).isEqualTo(EtlRunResult.Side.PROGRAM);
        assertThat(task.getLastRunSuccess()).isFalse();
        verify(runner, never()).run(any(), any(), any(), any());
    }

    /**
     * A crash the copy loop does not catch (an unreadable task row, a dead driver while opening
     * connections) must still leave a failed last-run and one history row. Logging alone left the
     * task showing its previous successful run as if the trigger had never happened.
     */
    @Test
    void aCrashOutsideTheCopyLoopStillLeavesAFailedRun() {
        EtlTask task = existing(7L, "SELECT id FROM src");
        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        stubConnections(1L, 2L);
        when(runner.run(eq(task), any(), any(), any()))
                .thenThrow(new IllegalStateException("driver died"));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.submit(7L)).isTrue();

        assertThat(task.getLastRunSuccess()).isFalse();
        assertThat(task.getLastRunResult()).contains("FAILED").contains("driver died");
        verify(runRepository).save(any(EtlRun.class));
        assertThat(guard.isRunning(7L)).isFalse();
    }

    // --- helpers ------------------------------------------------------------------------

    private void stubConnections(Long sourceId, Long targetId) {
        DatabaseConfig source = config(sourceId);
        DatabaseConfig target = config(targetId);
        when(databaseConfigService.findById(sourceId)).thenReturn(Optional.of(source));
        if (!sourceId.equals(targetId)) {
            when(databaseConfigService.findById(targetId)).thenReturn(Optional.of(target));
        }
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    private static EtlTask form(Long sourceId, Long targetId) {
        EtlTask task = new EtlTask();
        task.setName("nightly orders");
        task.setSourceDbId(sourceId);
        task.setTargetDbId(targetId);
        task.setSourceSql("SELECT id, name FROM src");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static EtlTask existing(Long id, String sql) {
        EtlTask task = form(1L, 2L);
        task.setId(id);
        task.setSourceSql(sql);
        return task;
    }
}
