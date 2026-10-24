package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The advisory cost model behind the FULL_COMPARE load warning.
 *
 * <p>A full-compare cycle reads the whole source table and upserts every row into the target
 * on each poll interval. At the global 2-second default that is only comfortable for small
 * tables; this model names the tables whose estimated cycle time approaches the interval.
 * The rate is deliberately conservative and exists only to drive a warning, never scheduling.
 */
class FullCompareLoadEstimatorTest {

    @Test
    void oneThousandRowsAtTwoSecondsIsStillComfortable() {
        assertThat(FullCompareLoadEstimator.isHeavy(1_000L, 2_000L)).isFalse();
    }

    @Test
    void fifteenThousandRowsAtTwoSecondsIsHeavy() {
        assertThat(FullCompareLoadEstimator.isHeavy(15_000L, 2_000L)).isTrue();
    }

    @Test
    void fiveThousandRowsAtTenSecondsIsStillComfortable() {
        assertThat(FullCompareLoadEstimator.isHeavy(5_000L, 10_000L)).isFalse();
    }

    @Test
    void theSuggestedIntervalLeavesRoomForTheEstimatedCycle() {
        // 20 000 rows at a conservative 1 000 rows/s is ~20 s of work per cycle; half the
        // interval should stay free, so the suggestion is 40 s.
        assertThat(FullCompareLoadEstimator.recommendedIntervalMs(20_000L))
                .isEqualTo(40_000L);
        assertThat(FullCompareLoadEstimator.recommendedIntervalMs(5_000L))
                .isEqualTo(10_000L);
    }

    @Test
    void aTinyTableStillGetsASaneMinimumInterval() {
        assertThat(FullCompareLoadEstimator.recommendedIntervalMs(10L)).isEqualTo(5_000L);
    }

    @Test
    void unknownOrEmptySizesAreNeverHeavy() {
        assertThat(FullCompareLoadEstimator.isHeavy(0L, 2_000L)).isFalse();
        assertThat(FullCompareLoadEstimator.isHeavy(-1L, 2_000L)).isFalse();
    }

    @Test
    void theTaskIntervalWinsOverTheGlobalDefault() {
        com.qqmu.jync.dto.SyncConfig config = new com.qqmu.jync.dto.SyncConfig();
        config.setPollIntervalMs(60_000L);
        assertThat(FullCompareLoadEstimator.effectiveIntervalMs(config, 2_000L))
                .isEqualTo(60_000L);

        config.setPollIntervalMs(null);
        assertThat(FullCompareLoadEstimator.effectiveIntervalMs(config, 2_000L))
                .isEqualTo(2_000L);

        config.setPollIntervalMs(0L);
        assertThat(FullCompareLoadEstimator.effectiveIntervalMs(config, 2_000L))
                .isEqualTo(2_000L);
    }
}
