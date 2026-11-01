package com.qqmu.jync.service.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Everything the task performance page draws: the task's own throughput, lag and failure
 * series. JVM process resources are not part of it — they belong to the machine, not to one
 * task, and live on the dashboard.
 *
 * <p>{@code changeShare} feeds the change-type ring and {@code hotObjects} the hottest-table
 * ranking; both come from the change log over the same window.
 */
public record ProjectMetricsView(
        // The page feeds this to new Date(); the app's own ObjectMapper writes an Instant as a
        // decimal epoch by default, which the browser reads as milliseconds (i.e. 1970).
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSample,
        String status, List<ChartSeries> series,
        Map<String, Double> current, List<SeriesPoint> changeShare,
        List<SeriesPoint> hotObjects) {
}
