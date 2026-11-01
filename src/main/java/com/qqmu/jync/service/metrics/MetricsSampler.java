package com.qqmu.jync.service.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
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

import lombok.extern.slf4j.Slf4j;

/**
 * Writes the process sample and the per-project throughput sample.
 *
 * <p>Sampling is a sidecar: each project is sampled inside its own catch, so one broken
 * project cannot cost the others their point, and nothing ever escapes into the scheduler —
 * an exception from a fixed-delay task cancels every subsequent run.
 */
@Component
@Slf4j
public class MetricsSampler {

    private final ProjectRepository projectRepository;
    private final SyncProgressRepository progressRepository;
    private final TaskMetricSampleRepository taskMetricRepository;
    private final AppMetricSampleRepository appMetricRepository;
    private final SyncTaskStore taskStore;
    private final CdcConsumerManager cdcConsumerManager;
    private final SystemMetrics systemMetrics;
    private final ProcessSample.Reader processReader;
    private final MetricsProperties properties;
    private final Clock clock;

    @Autowired
    public MetricsSampler(ProjectRepository projectRepository,
                          SyncProgressRepository progressRepository,
                          TaskMetricSampleRepository taskMetricRepository,
                          AppMetricSampleRepository appMetricRepository,
                          SyncTaskStore taskStore,
                          CdcConsumerManager cdcConsumerManager,
                          SystemMetrics systemMetrics,
                          MetricsProperties properties) {
        this(projectRepository, progressRepository, taskMetricRepository, appMetricRepository,
                taskStore, cdcConsumerManager, systemMetrics, ProcessSample::capture, properties,
                Clock.systemUTC());
    }

    /** Test seam: a fixed clock and a fake process reader, so no test waits a minute. */
    MetricsSampler(ProjectRepository projectRepository,
                   SyncProgressRepository progressRepository,
                   TaskMetricSampleRepository taskMetricRepository,
                   AppMetricSampleRepository appMetricRepository,
                   SyncTaskStore taskStore,
                   CdcConsumerManager cdcConsumerManager,
                   SystemMetrics systemMetrics,
                   ProcessSample.Reader processReader,
                   MetricsProperties properties,
                   Clock clock) {
        this.projectRepository = projectRepository;
        this.progressRepository = progressRepository;
        this.taskMetricRepository = taskMetricRepository;
        this.appMetricRepository = appMetricRepository;
        this.taskStore = taskStore;
        this.cdcConsumerManager = cdcConsumerManager;
        this.systemMetrics = systemMetrics;
        this.processReader = processReader;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${metrics.app-interval-ms:60000}",
            initialDelayString = "${metrics.app-interval-ms:60000}")
    public void sampleApp() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ProcessSample sample = processReader.read(systemMetrics.snapshot());
            AppMetricSample row = new AppMetricSample();
            row.setSampledAt(clock.instant());
            row.setCpuPercent(sample.cpuPercent());
            row.setHeapUsed(sample.heapUsed());
            row.setHeapMax(sample.heapMax());
            row.setNonheapUsed(sample.nonheapUsed());
            row.setThreads(sample.threads());
            row.setGcCount(sample.gcCount());
            row.setGcTimeMs(sample.gcTimeMs());
            row.setSysLoadPercent(sample.sysLoadPercent());
            row.setSysMemPercent(sample.sysMemPercent());
            row.setDiskPercent(sample.diskPercent());
            appMetricRepository.save(row);
        } catch (Exception e) {
            log.warn("Process metric sample failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${metrics.task-interval-ms:60000}",
            initialDelayString = "${metrics.task-interval-ms:60000}")
    public void sampleTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        for (Project project : projectRepository.findByEnabledTrue()) {
            try {
                taskMetricRepository.save(taskSample(project.getId(), now));
            } catch (Exception e) {
                log.warn("Task metric sample failed for project {}: {}", project.getId(),
                        e.getMessage());
            }
        }
    }

    private TaskMetricSample taskSample(Long projectId, Instant now) {
        List<SyncProgress> progress = progressRepository.findByProjectId(projectId);
        long progressRows = progress.stream()
                .mapToLong(p -> p.getRowsSyncedTotal() == null ? 0L : p.getRowsSyncedTotal())
                .sum();
        // A CDC consumer's changes never reach SyncProgress: the polling engine writes the
        // baseline and then stops, so those rows are the only record of what CDC delivered —
        // without them a streaming project charts as permanently idle. A consumer owned by
        // this node supersedes both figures; lagSeconds() is -1 while disconnected, which
        // must read as "unknown", never as the frozen baseline timestamp.
        CdcRuntimeStats cdc = cdcConsumerManager.statsFor(projectId);
        long total = progressRows + (cdc == null ? 0L : cdc.getRowsApplied());
        Long cdcLagMs = null;
        if (cdc != null) {
            long lagSeconds = cdc.lagSeconds();
            cdcLagMs = lagSeconds < 0 ? null : lagSeconds * 1000L;
        }

        TaskMetricSample row = new TaskMetricSample();
        row.setProjectId(projectId);
        row.setSampledAt(now);
        row.setRowsTotal(total);
        // A running total cannot be charted as a rate, so each point carries the growth since
        // the previous one. The very first sample after a restart has nothing to subtract
        // from — null, not zero, or the chart would show a minute of no traffic. A negative
        // difference means the total was reset (清空进度, a rebuilt CDC baseline), not that
        // rows left the target, so that sample is a gap too — the same rule the database
        // sampler applies to its statement counter.
        row.setRowsDelta(taskMetricRepository.findTopByProjectIdOrderBySampledAtDesc(projectId)
                .map(previous -> {
                    if (previous.getRowsTotal() == null) {
                        return null;
                    }
                    long delta = total - previous.getRowsTotal();
                    return delta < 0 ? null : delta;
                })
                .orElse(null));
        SyncTask task = taskStore.find(projectId).orElse(null);
        row.setLagMs(cdc != null ? cdcLagMs : pollingLagMs(task, now));
        row.setConsecutiveFailures(task == null ? null : task.getConsecutiveFailures());
        row.setStatus(task == null || task.getStatus() == null ? null : task.getStatus().name());
        return row;
    }

    /**
     * Delay for a polling project: how long since the engine last finished a clean pass.
     *
     * <p>Not "how stale is the data" — progress only advances when rows land, so an idle
     * source would otherwise show hours of "delay" while the engine is polling every second.
     * Unknown when the task is not running, or has not completed a pass yet: there is no
     * heartbeat to measure from, and a stale timestamp must not stand in for one.
     */
    private Long pollingLagMs(SyncTask task, Instant now) {
        if (task == null || task.getStatus() != TaskStatus.RUNNING
                || task.getLastSuccessAt() == null) {
            return null;
        }
        // A clock that stepped backwards must not chart a negative delay.
        return Math.max(0L, Duration.between(task.getLastSuccessAt(), now).toMillis());
    }
}
