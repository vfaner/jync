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
 * from the outside. Splitting the pass into read, row-work and commit shares is what turns
 * "slow" into a number someone can act on: a dominant commit share means redo-log or storage
 * latency (fewer, larger commits), a dominant read share means the source cursor, and one
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
    private long queryNanos;
    private long readNanos;
    private long writeNanos;
    private long commitNanos;
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
     * Preparing and executing the page query, up to the moment the first row is available.
     *
     * <p>Charged to its own share because its remedy is the source's plan or load, not the
     * row loop: without it a query that scans instead of seeking leaves a gap between chunk
     * lines and nothing in the line to explain it.
     */
    void recordQuery(long nanos) {
        queryNanos += nanos;
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
     * batch filled — {@code executeBatch}. A commit that happens inside this window is
     * measured by {@link #recordCommit(long)} and reported apart from the row work.
     */
    void recordWrite(long nanos) {
        writeNanos += nanos;
    }

    /**
     * The target's {@code COMMIT} call. Reported on its own because it waits on things the
     * row loop cannot influence — redo-log sync, a Data Guard stand-by, slow storage — and
     * the remedy is fewer, larger commits, not a faster write loop.
     */
    void recordCommit(long nanos) {
        commitNanos += nanos;
    }

    /** One {@code executeBatch} call; the count per row is what exposes a driver not batching. */
    void recordBatch() {
        batches++;
    }

    int rows() {
        return rows;
    }

    long queryNanos() {
        return queryNanos;
    }

    long readNanos() {
        return readNanos;
    }

    long writeNanos() {
        return writeNanos;
    }

    long commitNanos() {
        return commitNanos;
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
     * be attributed to the code path that ran it. {@code changedRows} is next to {@code rows}
     * because the two differ exactly when the pass re-read an unchanged table.
     */
    String describe(int changedRows) {
        long measured = queryNanos + readNanos + writeNanos;
        // Write windows include the commit that closed a batch; report row work net of it.
        long rowWorkNanos = Math.max(0L, writeNanos - commitNanos);
        // Each share is the difference of two rounded cumulative points, not its own rounded
        // ratio: rounding three ratios independently can sum to 101 (33.5 + 33.5 + 32.5
        // rounds to 34 + 34 + 33) and leave the remainder negative, which both overstates the
        // total and hides the commit share. Cumulative differences always sum to 100.
        int queryPct = percentOf(queryNanos, measured);
        int readPct = percentOf(queryNanos + readNanos, measured) - queryPct;
        int writePct = percentOf(queryNanos + readNanos + rowWorkNanos, measured)
                - queryPct - readPct;
        // Nothing measured is a pass that ended before the clock ticked: every share is 0,
        // not a 100% "commit" made of nothing.
        int commitPct = measured == 0 ? 0 : 100 - queryPct - readPct - writePct;
        double rowsPerBatch = batches == 0 ? 0 : (double) rows / batches;
        return String.format(Locale.ROOT,
                "table=%s thread=%s rows=%d changed=%d elapsed=%.1fs rows/s=%.1f"
                        + " query=%d%% read=%d%% write=%d%% commit=%d%%"
                        + " batches=%d rowsPerBatch=%.0f",
                table, Thread.currentThread().getName(), rows, changedRows,
                elapsedNanos() / 1e9, rowsPerSecond(), queryPct, readPct, writePct, commitPct,
                batches, rowsPerBatch);
    }

    /** Rounded cumulative share; zero when nothing was measured. */
    private static int percentOf(long part, long whole) {
        return whole <= 0 ? 0 : (int) Math.round(part * 100.0 / whole);
    }

    /**
     * Ends the pass. INFO only when the target actually moved: a full-compare project re-reads
     * its whole table every poll interval, so keying INFO on the row count alone made an idle
     * target look busy. Unchanged passes and small windows/tails go to DEBUG.
     */
    void logSummary(int changedRows) {
        if (rows >= SMALL_PASS_ROWS && changedRows > 0) {
            log.info("{}", describe(changedRows));
        } else {
            log.debug("{}", describe(changedRows));
        }
    }
}
