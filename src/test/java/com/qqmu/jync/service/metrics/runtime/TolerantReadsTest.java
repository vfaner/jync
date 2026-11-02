package com.qqmu.jync.service.metrics.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Two shapes of cache statistics: disjoint hit/read counters (Postgres) and logical reads
 * that contain their own physical reads (InnoDB, DB2, Oracle). Mixing them up understates a
 * cold cache — 500 disk reads out of 1000 logical reads is a 50% hit rate, not 67%.
 */
class TolerantReadsTest {

    @Test
    void disjointCountersDivideHitsByAllReads() {
        assertThat(TolerantReads.hitPercent(9900L, 100L)).isEqualTo(99.0);
        assertThat(TolerantReads.hitPercent(0L, 0L)).isNull();
        assertThat(TolerantReads.hitPercent(null, 1L)).isNull();
    }

    @Test
    void nestedCountersDivideTheCacheServedPartByLogicalReads() {
        assertThat(TolerantReads.hitPercentOfLogicalReads(1000L, 10L)).isEqualTo(99.0);
        assertThat(TolerantReads.hitPercentOfLogicalReads(1000L, 500L)).isEqualTo(50.0);
        assertThat(TolerantReads.hitPercentOfLogicalReads(1000L, 1000L)).isEqualTo(0.0);
    }

    @Test
    void missingOrDisagreeingNestedCountersLeaveTheRateUnknown() {
        assertThat(TolerantReads.hitPercentOfLogicalReads(null, 1L)).isNull();
        assertThat(TolerantReads.hitPercentOfLogicalReads(1L, null)).isNull();
        assertThat(TolerantReads.hitPercentOfLogicalReads(0L, 0L)).isNull();
        assertThat(TolerantReads.hitPercentOfLogicalReads(1000L, -1L)).isNull();
        // Oracle direct-path reads bypass the buffer cache and are not session logical reads.
        assertThat(TolerantReads.hitPercentOfLogicalReads(1000L, 1001L)).isNull();
    }
}
