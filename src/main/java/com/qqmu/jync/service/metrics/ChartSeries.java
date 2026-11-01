package com.qqmu.jync.service.metrics;

import java.util.List;

/** One line (or one bar set) on a chart. {@code key} is stable for the JS side; {@code label} is the server's wording. */
public record ChartSeries(String key, String label, List<SeriesPoint> points) {
}
