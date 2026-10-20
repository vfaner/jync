package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

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
        assertThat(meter.describe()).contains("rows/s=0.0");
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
        assertThat(meter.describe())
                .contains("table=ORDERS")
                .contains("rows=1000")
                .contains("read=20%")
                .contains("write=80%")
                .contains("batches=2")
                .contains("rowsPerBatch=500");
    }
}
