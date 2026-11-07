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
 * One probe of one connection: health and latency every few minutes, capacity on a slower
 * schedule.
 *
 * <p>Nullable columns mean "this product does not report it", and they are deliberately
 * distinct from zero — a chart must be able to tell "no sessions reported" from "zero
 * sessions". {@code message} is filled only on failure, so the page can say why the dot is
 * red instead of just that it is.
 */
@Entity
@Table(name = "db_metric_sample", indexes = {
        @Index(name = "idx_db_metric_conn_time", columnList = "connection_id, sampled_at")
})
@Getter
@Setter
public class DbMetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(nullable = false)
    private boolean ok;

    @Column(name = "latency_ms")
    private Long latencyMs;

    private Integer sessions;

    private Double qps;

    @Column(name = "cache_hit_percent")
    private Double cacheHitPercent;

    @Column(name = "lock_waits")
    private Integer lockWaits;

    @Column(name = "table_count")
    private Integer tableCount;

    @Column(name = "row_count")
    private Long rowCount;

    private Long bytes;

    @Column(length = 512)
    private String message;
}
