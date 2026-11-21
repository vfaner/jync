package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The per-minute task sample turns a running row total into a rate, and reports the task's
 * own verdict. One project's failure must not cost the other projects their point — the
 * chart would show a gap that never happened.
 */
@ExtendWith(MockitoExtension.class)
class MetricsSamplerTaskTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Mock private ProjectRepository projectRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private TaskMetricSampleRepository taskMetricRepository;
    @Mock private AppMetricSampleRepository appMetricRepository;
    @Mock private SyncTaskStore taskStore;
    @Mock private CdcConsumerManager cdcConsumerManager;
    @Mock private SystemMetrics systemMetrics;
    @Mock private ProcessSample.Reader processReader;

    private MetricsProperties properties;
    private MetricsSampler sampler;

    @BeforeEach
    void setUp() {
        properties = new MetricsProperties();
        sampler = new MetricsSampler(projectRepository, progressRepository, taskMetricRepository,
                appMetricRepository, taskStore, cdcConsumerManager, systemMetrics, processReader,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theRowDeltaIsTheDifferenceFromThePreviousSample() {
        Project project = project(1L);
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(160L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.of(previous(1L, 100L)));

        sampler.sampleTasks();

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getRowsTotal()).isEqualTo(160);
        assertThat(saved.getRowsDelta()).isEqualTo(60);
        assertThat(saved.getSampledAt()).isEqualTo(NOW);
    }

    @Test
    void theFirstSampleHasNoDelta() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(160L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());

        sampler.sampleTasks();

        assertThat(savedTaskSample().getRowsDelta()).isNull();
    }

    @Test
    void aRunningCdcConsumerAddsItsAppliedRowsToTheTotal() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        // The baseline wrote 100 rows into the progress table; everything after it is
        // applied by the CDC consumer and never touches SyncProgress.
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.of(previous(1L, 100L)));
        CdcRuntimeStats stats = runningConsumer(25L);
        when(cdcConsumerManager.statsFor(1L)).thenReturn(stats);

        sampler.sampleTasks();

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getRowsTotal()).isEqualTo(125);
        assertThat(saved.getRowsDelta()).isEqualTo(25);
    }

    @Test
    void aRunningCdcConsumerMeasuresLagFromItsOwnLastEvent() {
        // For a CDC project the polling baseline's timestamps never move again; lag must come
        // from the consumer's own last event, not from a frozen progress/explicit pass time.
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        when(cdcConsumerManager.statsFor(1L)).thenReturn(runningConsumer(0L));

        sampler.sampleTasks();

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getLagMs()).isNotNull().isLessThan(Duration.ofMinutes(1).toMillis());
    }

    @Test
    void aDisconnectedCdcConsumerLeavesLagEmpty() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.setConnected(false);
        when(cdcConsumerManager.statsFor(1L)).thenReturn(stats);

        sampler.sampleTasks();

        assertThat(savedTaskSample().getLagMs()).isNull();
    }

    /**
     * A polling project's progress timestamp only moves when rows land, so an idle source
     * leaves it hours behind while the engine keeps polling every second. Reporting that
     * distance as "replication lag" made a healthy idle project look permanently stalled —
     * the card must instead say how long ago the last cycle finished.
     */
    @Test
    void aRunningPollingTaskMeasuresLagFromItsLastSuccessfulCycle() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        SyncTask task = new SyncTask();
        task.setStatus(TaskStatus.RUNNING);
        task.setLastSuccessAt(NOW.minusSeconds(2));
        when(taskStore.find(1L)).thenReturn(Optional.of(task));

        sampler.sampleTasks();

        assertThat(savedTaskSample().getLagMs()).isEqualTo(2_000L);
    }

    @Test
    void aStoppedPollingTaskHasNoLagReading() {
        // Nothing is polling, so "how long since the last cycle" is not a delay reading —
        // it is just the age of a stopped engine. The lag card must stay empty.
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        SyncTask task = new SyncTask();
        task.setStatus(TaskStatus.STOPPED);
        task.setLastSuccessAt(NOW.minusSeconds(2));
        when(taskStore.find(1L)).thenReturn(Optional.of(task));

        sampler.sampleTasks();

        assertThat(savedTaskSample().getLagMs()).isNull();
    }

    @Test
    void aRunningPollingTaskWithoutASuccessfulCycleYetHasNoLagReading() {
        // Just started: the first cycle has not finished, so there is nothing to measure
        // from — the stale progress timestamp must not stand in for it.
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(100L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        SyncTask task = new SyncTask();
        task.setStatus(TaskStatus.RUNNING);
        when(taskStore.find(1L)).thenReturn(Optional.of(task));

        sampler.sampleTasks();

        assertThat(savedTaskSample().getLagMs()).isNull();
    }

    @Test
    void aProgressResetDoesNotProduceANegativeDelta() {
        // 清空进度 (and a CDC baseline rebuild) deletes the progress rows, so the running total
        // drops back to zero. Nothing was un-synced; the sample that sees the drop cannot tell
        // how many rows the reset hid, so it must leave a gap rather than chart a negative bar.
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(40L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.of(previous(1L, 100L)));

        sampler.sampleTasks();

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getRowsTotal()).isEqualTo(40);
        assertThat(saved.getRowsDelta()).isNull();
    }

    @Test
    void theTaskVerdictIsCopiedFromTheTaskRow() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenReturn(10L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(1L))
                .thenReturn(Optional.empty());
        SyncTask task = new SyncTask();
        task.setConsecutiveFailures(3);
        task.setStatus(TaskStatus.ERROR);
        when(taskStore.find(1L)).thenReturn(Optional.of(task));

        sampler.sampleTasks();

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo("ERROR");
    }

    @Test
    void oneFailingProjectDoesNotSkipTheOthers() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L), project(2L)));
        when(progressRepository.sumRowsSyncedByProject(1L)).thenThrow(new IllegalStateException("boom"));
        when(progressRepository.sumRowsSyncedByProject(2L)).thenReturn(5L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(2L))
                .thenReturn(Optional.empty());

        assertThatCode(() -> sampler.sampleTasks()).doesNotThrowAnyException();

        ArgumentCaptor<TaskMetricSample> captor = ArgumentCaptor.forClass(TaskMetricSample.class);
        verify(taskMetricRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(2L);
    }

    @Test
    void aTurnedOffSamplerWritesNothing() {
        properties.setEnabled(false);

        sampler.sampleTasks();
        sampler.sampleApp();
        sampler.sampleProject(1L);

        verifyNoInteractions(projectRepository, taskMetricRepository, appMetricRepository);
    }

    /**
     * 立即同步 deliberately runs one cycle on a stopped project. The periodic sampler only
     * visits enabled projects, so without an on-demand point its performance page stays empty
     * no matter how many rows the cycle moved.
     */
    @Test
    void anOnDemandSampleRecordsAProjectThePeriodicSamplerWouldSkip() {
        when(progressRepository.sumRowsSyncedByProject(7L))
                .thenReturn(10_000_052L);
        when(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(7L))
                .thenReturn(Optional.empty());

        sampler.sampleProject(7L);

        TaskMetricSample saved = savedTaskSample();
        assertThat(saved.getProjectId()).isEqualTo(7L);
        assertThat(saved.getRowsTotal()).isEqualTo(10_000_052L);
        assertThat(saved.getSampledAt()).isEqualTo(NOW);
    }

    @Test
    void anOnDemandSampleFailureIsContained() {
        // Metrics are a sidecar: a broken sample must never reach the sync cycle.
        when(progressRepository.sumRowsSyncedByProject(7L))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> sampler.sampleProject(7L)).doesNotThrowAnyException();

        verifyNoInteractions(taskMetricRepository);
    }

    @Test
    void theAppSampleIsWrittenWithTheCapturedProcessFigures() {
        when(processReader.read(any())).thenReturn(new ProcessSample(
                12.5, 1024L, 2048L, 512L, 40, 7L, 90L, 3.0, 55.0, 61.0));

        sampler.sampleApp();

        ArgumentCaptor<com.qqmu.jync.model.AppMetricSample> captor =
                ArgumentCaptor.forClass(com.qqmu.jync.model.AppMetricSample.class);
        verify(appMetricRepository).save(captor.capture());
        assertThat(captor.getValue().getHeapUsed()).isEqualTo(1024L);
        assertThat(captor.getValue().getThreads()).isEqualTo(40);
        assertThat(captor.getValue().getSampledAt()).isEqualTo(NOW);
    }

    private TaskMetricSample savedTaskSample() {
        ArgumentCaptor<TaskMetricSample> captor = ArgumentCaptor.forClass(TaskMetricSample.class);
        verify(taskMetricRepository).save(captor.capture());
        return captor.getValue();
    }

    private Project project(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setName("p" + id);
        return project;
    }

    /** A consumer owned by this node: connected, with a just-recorded event. */
    private CdcRuntimeStats runningConsumer(long appliedRows) {
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.setConnected(true);
        stats.touchEvent(System.currentTimeMillis());
        stats.addUpserts(appliedRows);
        return stats;
    }

    private TaskMetricSample previous(Long projectId, long rows) {
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(projectId);
        sample.setSampledAt(NOW.minusSeconds(60));
        sample.setRowsTotal(rows);
        return sample;
    }
}
