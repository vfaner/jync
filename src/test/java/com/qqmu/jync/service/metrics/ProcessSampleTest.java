package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.GarbageCollectorMXBean;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.SystemMetrics;

/**
 * The process sample is what the task page shows as "Jync process": a null must mean
 * "the platform does not report this", never a figure that looks measured.
 */
class ProcessSampleTest {

    @Test
    void unsupportedCollectorCountersAreNotAddedIn() {
        // Windows and some collectors report -1 for "not supported"; adding that to a real
        // total would silently shave collections off the chart.
        long[] totals = ProcessSample.gcTotals(List.of(
                collector(10, 250),
                collector(-1, -1),
                collector(5, 100)));

        assertThat(totals).containsExactly(15, 350);
    }

    @Test
    void unknownSystemMetricsBecomeNullRatherThanTheNegativeSentinel() {
        ProcessSample sample = ProcessSample.of(SystemMetrics.Snapshot.UNKNOWN,
                1000, 2000, 500, 12, 1, 2);

        assertThat(sample.cpuPercent()).isNull();
        assertThat(sample.sysLoadPercent()).isNull();
        assertThat(sample.sysMemPercent()).isNull();
        assertThat(sample.diskPercent()).isNull();
    }

    @Test
    void memoryAndDiskAreTurnedIntoPercentages() {
        // 1 GiB used of 2 GiB total, and 250 MiB of 1 GiB.
        ProcessSample sample = ProcessSample.of(
                new SystemMetrics.Snapshot(10, 1.5, 2, 42, -1, 1024L * 1024 * 1024,
                        2L * 1024 * 1024 * 1024, 256L * 1024 * 1024, 1024L * 1024 * 1024, "/data"),
                1000, 2000, 500, 12, 1, 2);

        assertThat(sample.sysLoadPercent()).isEqualTo(10);
        assertThat(sample.sysMemPercent()).isEqualTo(50);
        assertThat(sample.diskPercent()).isEqualTo(25);
    }

    /**
     * The card is labelled 进程 CPU: it must show the JVM's own share of the CPU. The host
     * figure (42% here) belongs to the dashboard's host gauge, not to this row.
     */
    @Test
    void theProcessCpuFigureIsTheJvmsOwnLoad() {
        ProcessSample sample = ProcessSample.of(
                new SystemMetrics.Snapshot(10, 1.5, 2, 42, 7, 0, 0, 0, 0, "/data"),
                1000, 2000, 500, 12, 1, 2);

        assertThat(sample.cpuPercent()).isEqualTo(7);
    }

    @Test
    void anUnreadableProcessCpuStaysUnknownEvenWhenTheHostCpuIsKnown() {
        ProcessSample sample = ProcessSample.of(
                new SystemMetrics.Snapshot(10, 1.5, 2, 42, -1, 0, 0, 0, 0, "/data"),
                1000, 2000, 500, 12, 1, 2);

        assertThat(sample.cpuPercent()).isNull();
    }

    @Test
    void aZeroTotalDoesNotDivideByZero() {
        ProcessSample sample = ProcessSample.of(
                new SystemMetrics.Snapshot(-1, -1, 1, -1, -1, 0, 0, 0, 0, null),
                1000, 2000, 500, 12, 1, 2);

        assertThat(sample.sysMemPercent()).isNull();
        assertThat(sample.diskPercent()).isNull();
    }

    private static GarbageCollectorMXBean collector(long count, long time) {
        GarbageCollectorMXBean bean = mock(GarbageCollectorMXBean.class);
        when(bean.getCollectionCount()).thenReturn(count);
        when(bean.getCollectionTime()).thenReturn(time);
        return bean;
    }
}
