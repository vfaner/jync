package com.qqmu.jync.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * One minute of one project's throughput: the row total, how much it grew since the previous
 * sample, how stale the last successful sync is, and the current task verdict.
 *
 * <p>Only the synced-row total is measured; per-task memory is deliberately absent — every
 * task shares one JVM heap, so any per-task split would be invented.
 */
@Entity
@Table(name = "task_metric_sample", indexes = {
        @Index(name = "idx_task_metric_project_time", columnList = "project_id, sampled_at")
})
@Getter
@Setter
public class TaskMetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "rows_delta")
    private Long rowsDelta;

    @Column(name = "rows_total")
    private Long rowsTotal;

    @Column(name = "lag_ms")
    private Long lagMs;

    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures;

    @Column(length = 32)
    private String status;
}
