package com.qqmu.jync.service.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Everything the database performance page draws, from one query.
 *
 * <p>{@code ok}/{@code message} describe the newest sample so the page can show why the last
 * probe failed. {@code uptimePercent} is the share of successful samples in the window, null
 * when the window holds none. {@code current} holds the cards' values: instantaneous figures
 * come from the newest row even when that row says "unknown", while capacity (sampled hourly)
 * falls back to the newest sample that actually reported it.
 */
public record DbMetricsView(boolean ok, String message,
                            // Same reason as ProjectMetricsView: the poller calls new Date()
                            // on it, and a numeric epoch would read as 1970.
                            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSample,
                            Double uptimePercent, List<ChartSeries> series,
                            Map<String, Double> current) {
}
