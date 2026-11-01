package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The chart's honesty lives here: a quiet minute must be an empty bucket, not a zero, and a
 * sample on a bucket boundary must belong to exactly one bucket or the totals double-count.
 */
class MetricBucketTest {

    private static final Instant FROM = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T10:10:00Z");

    private record Sample(Instant at, Double value) {
    }

    @Test
    void everyWindowGetsABucketEvenWhenNothingHappened() {
        List<List<Sample>> buckets =
                MetricBucket.split(FROM, TO, Duration.ofMinutes(1), List.of(), Sample::at);

        assertThat(buckets).hasSize(10);
        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket).isEmpty());
    }

    @Test
    void aSampleOnABoundaryLandsInExactlyOneBucket() {
        List<Sample> samples = List.of(
                new Sample(Instant.parse("2026-11-01T10:03:00Z"), 1.0),
                new Sample(Instant.parse("2026-11-01T10:03:59.999Z"), 2.0));

        List<List<Sample>> buckets =
                MetricBucket.split(FROM, TO, Duration.ofMinutes(1), samples, Sample::at);

        assertThat(buckets.get(3)).hasSize(2);
        assertThat(buckets.stream().mapToInt(List::size).sum()).isEqualTo(2);
    }

    @Test
    void samplesOutsideTheHalfOpenWindowAreDropped() {
        List<Sample> samples = List.of(
                new Sample(Instant.parse("2026-11-01T09:59:59Z"), 1.0),
                new Sample(Instant.parse("2026-11-01T10:10:00Z"), 2.0),
                new Sample(Instant.parse("2026-11-01T10:05:00Z"), 3.0));

        List<List<Sample>> buckets =
                MetricBucket.split(FROM, TO, Duration.ofMinutes(1), samples, Sample::at);

        assertThat(buckets.stream().mapToInt(List::size).sum()).isEqualTo(1);
    }

    @Test
    void averagingSkipsNullsAndNothingAtAllIsNull() {
        assertThat(MetricBucket.average(java.util.Arrays.asList(1.0, null, 3.0))).isEqualTo(2.0);
        assertThat(MetricBucket.average(List.of())).isNull();
        assertThat(MetricBucket.average(java.util.Arrays.asList(null, null))).isNull();
    }

    @Test
    void summingSkipsNullsAndNothingAtAllIsNull() {
        assertThat(MetricBucket.sum(java.util.Arrays.asList(2.0, null, 3.0))).isEqualTo(5.0);
        assertThat(MetricBucket.sum(List.of())).isNull();
    }

    @Test
    void maxReportsTheWorstBucketOfFailuresNotTheirAverage() {
        assertThat(MetricBucket.max(List.of(0.0, 4.0, 1.0))).isEqualTo(4.0);
        assertThat(MetricBucket.max(List.of())).isNull();
    }
}
