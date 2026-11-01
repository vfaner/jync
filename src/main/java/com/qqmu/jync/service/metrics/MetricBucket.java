package com.qqmu.jync.service.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fixed-width time buckets for the chart endpoints, and the reductions applied inside one.
 *
 * <p>Two rules the charts depend on: every window gets a bucket whether or not anything
 * happened in it, and a reduction over only nulls is null rather than zero — a quiet minute
 * and a minute measured at zero are different facts, and the line must break for the first.
 *
 * <p>Sample times are half-open on both ends of the window: {@code [from, to)}. A sample
 * exactly on a bucket boundary belongs to the bucket starting there and to no other, so the
 * totals never double-count.
 */
public final class MetricBucket {

    private MetricBucket() {
    }

    public static <T> List<List<T>> split(Instant from, Instant to, Duration size,
                                          List<T> samples, Function<T, Instant> at) {
        int count = bucketCount(from, to, size);
        List<List<T>> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            buckets.add(new ArrayList<>());
        }
        long width = size.toMillis();
        for (T sample : samples) {
            Instant time = at.apply(sample);
            if (time == null || time.isBefore(from) || !time.isBefore(to)) {
                continue;
            }
            int index = (int) (Duration.between(from, time).toMillis() / width);
            if (index >= 0 && index < count) {
                buckets.get(index).add(sample);
            }
        }
        return buckets;
    }

    public static int bucketCount(Instant from, Instant to, Duration size) {
        long span = Duration.between(from, to).toMillis();
        long width = size.toMillis();
        if (span <= 0 || width <= 0) {
            return 0;
        }
        return (int) ((span + width - 1) / width);
    }

    public static Double average(List<Double> values) {
        List<Double> present = present(values);
        if (present.isEmpty()) {
            return null;
        }
        double total = 0;
        for (Double value : present) {
            total += value;
        }
        return total / present.size();
    }

    public static Double sum(List<Double> values) {
        List<Double> present = present(values);
        if (present.isEmpty()) {
            return null;
        }
        double total = 0;
        for (Double value : present) {
            total += value;
        }
        return total;
    }

    public static Double max(List<Double> values) {
        List<Double> present = present(values);
        if (present.isEmpty()) {
            return null;
        }
        double highest = present.get(0);
        for (Double value : present) {
            highest = Math.max(highest, value);
        }
        return highest;
    }

    private static List<Double> present(List<Double> values) {
        List<Double> present = new ArrayList<>(values.size());
        for (Double value : values) {
            if (value != null) {
                present.add(value);
            }
        }
        return present;
    }
}
