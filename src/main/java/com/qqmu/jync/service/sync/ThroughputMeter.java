package com.qqmu.jync.service.sync;

import java.util.Locale;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-copy-pass accounting: how many rows went through, how long the source spent producing
 * them and how long the target spent taking them.
 *
 * <p>A full load used to report only a final row count, which said nothing about whether the
 * bottleneck was reading, binding, buffering or committing — the three are indistinguishable
 * from the outside. Splitting the pass into a read share and a write share is what turns
 * "slow" into a number someone can act on: a dominant write share with few batches means
 * commit frequency, a dominant read share means the source cursor, and a write share with one
 * {@code executeBatch} call per batch says the driver really is batching.
 *
 * <p>Not a bean and not shared: one instance lives inside one {@link DataSyncService#copyRows}
 * call, on that call's thread. Parallel full-load workers therefore each print their own line.
 */
@Slf4j
final class ThroughputMeter {

    /** Below this many rows a pass is a small window/tail; its line is DEBUG, not INFO. */
    private static final int SMALL_PASS_ROWS = 1000;

    private final String table;
    private final LongSupplier nanoClock;
    private final long startedNanos;

    private int rows;
    private long readNanos;
    private long writeNanos;
    private int batches;

    ThroughputMeter(String table) {
        this(table, System::nanoTime);
    }

    /** Visible for tests: a clock they can move without sleeping. */
    ThroughputMeter(String table, LongSupplier nanoClock) {
        this.table = table;
        this.nanoClock = nanoClock;
        this.startedNanos = nanoClock.getAsLong();
    }

    /**
     * One row was produced by the source cursor: {@code rs.next()} plus the row mapping.
     */
    void recordRead(long nanos) {
        rows++;
        readNanos += nanos;
    }

    /**
     * One row was handed to the target: cleansing, binding, {@code addBatch}, and — when the
     * batch filled — {@code executeBatch} plus its commit.
     */
    void recordWrite(long nanos) {
        writeNanos += nanos;
    }

    /** One {@code executeBatch} call; the count per row is what exposes a driver not batching. */
    void recordBatch() {
        batches++;
    }

    int rows() {
        return rows;
    }

    long readNanos() {
        return readNanos;
    }

    long writeNanos() {
        return writeNanos;
    }

    int batches() {
        return batches;
    }

    long elapsedNanos() {
        return nanoClock.getAsLong() - startedNanos;
    }

    /** Zero when the pass ended before the clock ticked, rather than an infinity or a NaN. */
    double rowsPerSecond() {
        long elapsed = elapsedNanos();
        return elapsed <= 0 ? 0 : rows * 1_000_000_000.0 / elapsed;
    }

    /**
     * One line naming the thread (parallel workers share a table but not a line), so a pass can
     * be attributed to the code path that ran it.
     */
    String describe() {
        long measured = readNanos + writeNanos;
        int readPct = measured == 0 ? 0 : (int) Math.round(readNanos * 100.0 / measured);
        int writePct = measured == 0 ? 0 : 100 - readPct;
        double rowsPerBatch = batches == 0 ? 0 : (double) rows / batches;
        return String.format(Locale.ROOT,
                "table=%s thread=%s rows=%d elapsed=%.1fs rows/s=%.1f read=%d%% write=%d%%"
                        + " batches=%d rowsPerBatch=%.0f",
                table, Thread.currentThread().getName(), rows, elapsedNanos() / 1e9,
                rowsPerSecond(), readPct, writePct, batches, rowsPerBatch);
    }

    /**
     * Ends the pass. INFO for a real load, DEBUG for the small windows and tails of polling
     * projects, whose per-cycle lines would otherwise bury the ones that matter.
     */
    void logSummary() {
        if (rows >= SMALL_PASS_ROWS) {
            log.info("{}", describe());
        } else {
            log.debug("{}", describe());
        }
    }
}
