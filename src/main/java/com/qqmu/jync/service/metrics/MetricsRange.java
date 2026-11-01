package com.qqmu.jync.service.metrics;

import java.time.Duration;

/**
 * The three windows the performance pages offer, each with the bucket width its point count
 * can be drawn at. A 1-minute bucket over 7 days would be 10,080 points — more pixels than
 * the chart has, and far more rows than the page needs to show a trend.
 */
public enum MetricsRange {

    LAST_HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1)),
    LAST_DAY("24h", Duration.ofHours(24), Duration.ofMinutes(10)),
    LAST_WEEK("7d", Duration.ofDays(7), Duration.ofHours(1));

    private final String key;
    private final Duration span;
    private final Duration bucket;

    MetricsRange(String key, Duration span, Duration bucket) {
        this.key = key;
        this.span = span;
        this.bucket = bucket;
    }

    public String key() {
        return key;
    }

    public Duration span() {
        return span;
    }

    public Duration bucket() {
        return bucket;
    }

    /** An unknown or missing query parameter falls back to a day, matching the endpoints' default. */
    public static MetricsRange parse(String raw) {
        for (MetricsRange range : values()) {
            if (range.key.equals(raw)) {
                return range;
            }
        }
        return LAST_DAY;
    }
}
