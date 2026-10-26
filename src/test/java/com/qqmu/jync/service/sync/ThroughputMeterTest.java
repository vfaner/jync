package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.qqmu.jync.service.cdc.LogCapture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The per-chunk throughput line is the only evidence of where a full load spends its time.
 * It must add up correctly, say which side dominates, and never divide by zero on a pass that
 * ends before the clock ticks.
 */
class ThroughputMeterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @Test
    void accumulatesRowsAndReadWriteTimeAcrossBatches() {
        ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);

        for (int i = 0; i < 200; i++) {
            meter.recordRead(1_000_000L);   // 1 ms reading the row
            meter.recordWrite(3_000_000L);  // 3 ms cleansing, binding and buffering it
            if (i % 100 == 99) {
                meter.recordBatch();
            }
        }

        assertThat(meter.rows()).isEqualTo(200);
        assertThat(meter.readNanos()).isEqualTo(200_000_000L);
        assertThat(meter.writeNanos()).isEqualTo(600_000_000L);
        assertThat(meter.batches()).isEqualTo(2);
    }

    @Test
    void rowsPerSecondIsZeroWhenNoTimeHasPassed() {
        ThroughputMeter meter = new ThroughputMeter("EMPTY", clock::get);
        meter.recordRead(5L);

        assertThat(meter.elapsedNanos()).isZero();
        assertThat(meter.rowsPerSecond()).isZero();
        assertThat(meter.describe(0)).contains("rows/s=0.0");
    }

    @Test
    void describeReportsThroughputSplitAndRowsPerBatch() {
        ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
        for (int i = 0; i < 1000; i++) {
            meter.recordRead(100_000L); // 100 ms total
            meter.recordWrite(400_000L); // 400 ms total
        }
        meter.recordBatch();
        meter.recordBatch();
        clock.set(2_000_000_000L); // one second after the start

        assertThat(meter.elapsedNanos()).isEqualTo(1_999_000_000L);
        assertThat(meter.rowsPerSecond()).isCloseTo(1000.0 / 1.999,
                org.assertj.core.data.Offset.offset(0.5));
        assertThat(meter.describe(0))
                .contains("table=ORDERS")
                .contains("rows=1000")
                .contains("read=20%")
                .contains("write=80%")
                .contains("commit=0%")
                .contains("batches=2")
                .contains("rowsPerBatch=500");
    }

    /**
     * The write share used to swallow {@code conn.commit()}, so a target whose commits take
     * seconds looked exactly like a target whose rows take seconds — and the two need
     * opposite fixes (fewer commits vs. a faster target). Commit waits are reported,
     * and taken out of the row-work share so the three percentages still sum to 100.
     */
    @Test
    void commitWaitIsReportedApartFromTheRowWorkThatPrecededIt() {
        ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
        for (int i = 0; i < 100; i++) {
            meter.recordRead(1_000_000L);   // 1 ms per row  -> 100 ms
            meter.recordWrite(3_000_000L);  // 3 ms per row  -> 300 ms, of which one commit
        }
        meter.recordCommit(100_000_000L);   // 100 ms inside the write windows above
        meter.recordBatch();

        assertThat(meter.commitNanos()).isEqualTo(100_000_000L);
        // writeNanos keeps the total window; describe() reports the row work net of commit.
        assertThat(meter.writeNanos()).isEqualTo(300_000_000L);
        assertThat(meter.describe(0))
                .contains("read=25%")
                .contains("write=50%")
                .contains("commit=25%");
    }

    /**
     * Time the source spends before the first row is available (preparing and executing the
     * page query) is part of the pass. Left out, a slow plan looks like a healthy row loop
     * with a mysterious gap between chunk lines.
     */
    @Test
    void queryWaitIsReportedAndCountedInTheShares() {
        ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
        meter.recordQuery(300_000_000L);    // 300 ms before the first row
        for (int i = 0; i < 100; i++) {
            meter.recordRead(3_000_000L);   // 300 ms total
            meter.recordWrite(4_000_000L);  // 400 ms total
        }

        assertThat(meter.queryNanos()).isEqualTo(300_000_000L);
        assertThat(meter.describe(0))
                .contains("query=30%")
                .contains("read=30%")
                .contains("write=40%")
                .contains("commit=0%");
    }

    /**
     * A full-compare table is re-read every poll interval, so an idle project produces a "big"
     * pass that changed nothing every couple of seconds. At INFO those lines read as data
     * moving when the target is untouched — they belong on DEBUG, with the change count in the
     * line so the two cases can be told apart even there.
     */
    @Test
    void aBigPassThatChangedNothingIsOnlyADebugLine() {
        ListAppender<ILoggingEvent> logs = attachAtDebug();

        try {
            ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
            streamRows(meter, 1185);
            meter.logSummary(0);

            assertThat(LogCapture.messagesAt(Level.INFO, logs)).isEmpty();
            assertThat(LogCapture.messagesAt(Level.DEBUG, logs))
                    .singleElement()
                    .asString()
                    .contains("rows=1185", "changed=0");
        } finally {
            detach(logs);
        }
    }

    @Test
    void aBigPassThatChangedRowsStaysOnInfo() {
        ListAppender<ILoggingEvent> logs = attachAtDebug();

        try {
            ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
            streamRows(meter, 1185);
            meter.logSummary(7);

            assertThat(LogCapture.messagesAt(Level.INFO, logs))
                    .singleElement()
                    .asString()
                    .contains("rows=1185", "changed=7");
            assertThat(LogCapture.messagesAt(Level.DEBUG, logs)).isEmpty();
        } finally {
            detach(logs);
        }
    }

    @Test
    void aSmallPassStaysOnDebugEvenWhenItChangedRows() {
        ListAppender<ILoggingEvent> logs = attachAtDebug();

        try {
            ThroughputMeter meter = new ThroughputMeter("ORDERS", clock::get);
            streamRows(meter, 3);
            meter.logSummary(3);

            assertThat(LogCapture.messagesAt(Level.INFO, logs)).isEmpty();
            assertThat(LogCapture.messagesAt(Level.DEBUG, logs))
                    .singleElement()
                    .asString()
                    .contains("rows=3", "changed=3");
        } finally {
            detach(logs);
        }
    }

    private static void streamRows(ThroughputMeter meter, int count) {
        for (int i = 0; i < count; i++) {
            meter.recordRead(1_000L);
            meter.recordWrite(1_000L);
        }
    }

    private static ListAppender<ILoggingEvent> attachAtDebug() {
        // The default test level is INFO; DEBUG lines must be captured to prove they exist.
        ((Logger) LoggerFactory.getLogger(ThroughputMeter.class)).setLevel(Level.DEBUG);
        return LogCapture.attach(ThroughputMeter.class);
    }

    private static void detach(ListAppender<ILoggingEvent> logs) {
        LogCapture.detach(ThroughputMeter.class, logs);
        ((Logger) LoggerFactory.getLogger(ThroughputMeter.class)).setLevel(null);
    }
}
