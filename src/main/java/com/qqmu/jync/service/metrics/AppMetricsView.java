package com.qqmu.jync.service.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * The process resources of the JVM every task on this server shares, as the dashboard card
 * draws them. One server, one set of numbers — which is exactly why they are not attached to
 * any single project.
 *
 * <p>{@code lastSample} is serialised as an ISO string for the same reason as the other two
 * views: the poller hands it to {@code new Date()}, and the app's own mapper would otherwise
 * write a numeric epoch that reads as 1970.
 */
public record AppMetricsView(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSample,
                             List<ChartSeries> series,
                             Map<String, Double> current) {
}
