package com.qqmu.jync.service.metrics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.MetricsProperties;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Enforces the {@code metrics.retention-days} window on the three sample tables.
 *
 * <p>Each table is pruned inside its own catch: the tables are independent, so a locked or
 * missing one must not strand the other two's history. Failures never escape into the
 * scheduler — an exception from a fixed-delay task cancels every subsequent run.
 */
@Component
@Slf4j
public class MetricRetentionPruner {

    private final DbMetricSampleRepository dbRepository;
    private final TaskMetricSampleRepository taskRepository;
    private final AppMetricSampleRepository appRepository;
    private final MetricsProperties properties;

    public MetricRetentionPruner(DbMetricSampleRepository dbRepository,
                                 TaskMetricSampleRepository taskRepository,
                                 AppMetricSampleRepository appRepository,
                                 MetricsProperties properties) {
        this.dbRepository = dbRepository;
        this.taskRepository = taskRepository;
        this.appRepository = appRepository;
        this.properties = properties;
    }

    /**
     * Daily, with the first run delayed so it never competes with startup. The transaction
     * lives on each repository method, deliberately not here, so a caught failure never has
     * to deal with a rollback-only mark.
     */
    @Scheduled(initialDelay = 10 * 60 * 1000L, fixedDelay = 24 * 60 * 60 * 1000L)
    public void prune() {
        int retentionDays = properties.getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        prune("db", () -> dbRepository.deleteOlderThan(cutoff));
        prune("task", () -> taskRepository.deleteOlderThan(cutoff));
        prune("app", () -> appRepository.deleteOlderThan(cutoff));
    }

    private void prune(String table, Runnable delete) {
        try {
            delete.run();
        } catch (Exception e) {
            log.warn("Pruning {} metric samples failed, will retry on the next run: {}",
                    table, e.getMessage());
        }
    }
}
