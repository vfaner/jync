package com.qqmu.jync.service.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;

import com.qqmu.jync.service.SystemMetrics;

/**
 * One reading of the JVM this instance runs in, in the terms {@code app_metric_sample}
 * stores.
 *
 * <p>Every figure is nullable on purpose: {@link SystemMetrics} reports "unknown" as a
 * negative number, and a chart that draws zero for "the platform has no number" would be
 * lying. The same rule applies to a collector that reports -1 for an unsupported counter —
 * it is left out of the total instead of subtracting from it.
 */
public record ProcessSample(Double cpuPercent, Long heapUsed, Long heapMax, Long nonheapUsed,
                            Integer threads, Long gcCount, Long gcTimeMs,
                            Double sysLoadPercent, Double sysMemPercent, Double diskPercent) {

    /** The seam that keeps {@link MetricsSampler} testable without touching the real JVM. */
    @FunctionalInterface
    public interface Reader {
        ProcessSample read(SystemMetrics.Snapshot snapshot);
    }

    /** Reads the live JVM. */
    static ProcessSample capture(SystemMetrics.Snapshot snapshot) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long[] gc = gcTotals(ManagementFactory.getGarbageCollectorMXBeans());
        return of(snapshot, heap.getUsed(), heap.getMax(), nonHeap.getUsed(), threads, gc[0], gc[1]);
    }

    static ProcessSample of(SystemMetrics.Snapshot snapshot, long heapUsed, long heapMax,
                            long nonheapUsed, int threads, long gcCount, long gcTimeMs) {
        return new ProcessSample(percent(snapshot.processCpuPercent()), heapUsed, heapMax,
                nonheapUsed, threads, gcCount, gcTimeMs, percent(snapshot.loadPercent()),
                ratio(snapshot.memoryUsedBytes(), snapshot.memoryTotalBytes()),
                ratio(snapshot.diskUsedBytes(), snapshot.diskTotalBytes()));
    }

    /** Sum of the collectors that actually report a number; -1 means "not supported". */
    static long[] gcTotals(List<GarbageCollectorMXBean> collectors) {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            if (collector.getCollectionCount() > 0) {
                count += collector.getCollectionCount();
            }
            if (collector.getCollectionTime() > 0) {
                time += collector.getCollectionTime();
            }
        }
        return new long[] {count, time};
    }

    private static Double percent(double value) {
        return value < 0 ? null : value;
    }

    /** The host gauges arrive as used/total byte pairs, not as percentages. */
    private static Double ratio(long used, long total) {
        return used < 0 || total <= 0 ? null : used * 100.0 / total;
    }
}
