package com.qqmu.jync.model;

import java.time.Instant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * One finished ETL run.
 *
 * <p>ETL tasks are not projects, so their runs cannot live in {@code change_log}, whose
 * {@code project_id} is mandatory. The run history is its own table — created by
 * {@code ddl-auto=update} on upgrade — and the dashboard's ETL line is drawn from it: the count
 * of runs and the rows they wrote per hour. {@link EtlTask} keeps only the latest outcome.
 */
@Entity
@Table(name = "etl_run", indexes = {
        @Index(name = "idx_etl_run_time", columnList = "occurred_at")
})
@Getter
@Setter
public class EtlRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable on purpose: deleting a task must not delete the history it left behind. */
    @Column(name = "etl_task_id")
    private Long etlTaskId;

    @Column(name = "affected_rows")
    private Integer affectedRows;

    /** False when the run failed; the chart still counts it — an attempt is activity. */
    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
