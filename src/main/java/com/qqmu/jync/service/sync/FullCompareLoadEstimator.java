package com.qqmu.jync.service.sync;

import com.qqmu.jync.dto.SyncConfig;

/**
 * Advisory cost model for a FULL_COMPARE project.
 *
 * <p>Every cycle re-reads the whole source table and upserts every row into the target. The
 * row cap only bounds the <em>automatic</em> strategy choice; an explicit project-level
 * FULL_COMPARE bypasses it, and even a table within the cap can be too heavy for a short
 * poll interval. This class answers two questions for the page and the start-up warning:
 * is this table too much work for the current interval, and what interval would leave room
 * for the cycle?
 *
 * <p>The rate is deliberately conservative ({@value #ROWS_PER_SECOND} rows/s for the whole
 * read-and-write path) and exists only to drive a warning — it never changes scheduling.
 */
public final class FullCompareLoadEstimator {

    /** Conservative sustained read-and-upsert rate used only for the advisory estimate. */
    private static final long ROWS_PER_SECOND = 1_000L;

    /** A cycle estimated to consume more than half the interval counts as heavy. */
    private static final double MAX_CYCLE_FRACTION = 0.5;

    private static final long MIN_RECOMMENDED_INTERVAL_MS = 5_000L;

    private FullCompareLoadEstimator() {
    }

    /** Whether this table's estimated full cycle takes more than half the interval. */
    public static boolean isHeavy(long rows, long intervalMs) {
        if (rows <= 0 || intervalMs <= 0) {
            return false;
        }
        double estimatedCycleSeconds = rows / (double) ROWS_PER_SECOND;
        double intervalSeconds = intervalMs / 1000.0;
        return estimatedCycleSeconds > intervalSeconds * MAX_CYCLE_FRACTION;
    }

    /**
     * An interval that leaves at least half the time free after the estimated cycle, with a
     * 5-second floor so tiny tables do not get absurd suggestions.
     */
    public static long recommendedIntervalMs(long rows) {
        long estimatedCycleSeconds =
                Math.max(1L, (long) Math.ceil(rows / (double) ROWS_PER_SECOND));
        return Math.max(MIN_RECOMMENDED_INTERVAL_MS, estimatedCycleSeconds * 2L * 1000L);
    }

    /** The task's own interval when set, otherwise the global default. */
    public static long effectiveIntervalMs(SyncConfig config, long fallbackMs) {
        if (config != null && config.getPollIntervalMs() != null
                && config.getPollIntervalMs() > 0) {
            return config.getPollIntervalMs();
        }
        return fallbackMs;
    }
}
