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
 * One minute of the Jync process itself: CPU, heap, threads, GC and the host gauges.
 *
 * <p>Process-level by nature — every sync task shares this heap — so it belongs to no project
 * and survives a project delete. Like the connection samples, a null means the platform did
 * not report the figure, never zero.
 */
@Entity
@Table(name = "app_metric_sample", indexes = {
        @Index(name = "idx_app_metric_time", columnList = "sampled_at")
})
@Getter
@Setter
public class AppMetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "cpu_percent")
    private Double cpuPercent;

    @Column(name = "heap_used")
    private Long heapUsed;

    @Column(name = "heap_max")
    private Long heapMax;

    @Column(name = "nonheap_used")
    private Long nonheapUsed;

    private Integer threads;

    @Column(name = "gc_count")
    private Long gcCount;

    @Column(name = "gc_time_ms")
    private Long gcTimeMs;

    @Column(name = "sys_load_percent")
    private Double sysLoadPercent;

    @Column(name = "sys_mem_percent")
    private Double sysMemPercent;

    @Column(name = "disk_percent")
    private Double diskPercent;
}
