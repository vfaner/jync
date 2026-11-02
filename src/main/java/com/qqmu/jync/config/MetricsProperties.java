package com.qqmu.jync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Performance-monitoring knobs, bound from the {@code metrics.*} prefix.
 *
 * <p>Sampling is a sidecar: turning it off costs the two performance pages only, never a sync
 * cycle. The intervals are separate because they buy different things — process and task
 * figures are cheap local reads, while a connection probe opens a session on the monitored
 * server and the capacity sweep reads table statistics.
 */
@ConfigurationProperties(prefix = "metrics")
@Getter
@Setter
public class MetricsProperties {

    /** Master switch. Off stops all four samplers; existing rows stay readable until pruned. */
    private boolean enabled = true;

    /** Days of history to keep; zero or less disables pruning and lets the tables grow. */
    private int retentionDays = 7;

    /** Interval for the process (CPU/heap/GC/host) sample. */
    private long appIntervalMs = 60_000L;

    /** Interval for the per-project throughput sample. */
    private long taskIntervalMs = 60_000L;

    /** Interval for the connection health and runtime-metrics sample. */
    private long dbIntervalMs = 300_000L;

    /**
     * Delay before the first connection sample. Short on purpose: a restart would otherwise
     * leave every performance page on "no samples yet" for a whole interval.
     */
    private long dbInitialDelayMs = 30_000L;

    /** Interval for the table/row/space sweep, which is the expensive one. */
    private long capacityIntervalMs = 3_600_000L;

    /**
     * Bearer token for {@code GET /api/metrics/prometheus}. Blank (the default) keeps the
     * endpoint closed: a stock installation must not expose project and connection names.
     */
    private String prometheusToken = "";
}
