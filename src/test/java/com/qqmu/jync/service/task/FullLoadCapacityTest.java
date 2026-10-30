package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;

/**
 * The pool-derived ceiling on full-load workers, shared by the engine (which clamps) and
 * the project page (which shows the number). One rule, one place — a second copy is how the
 * page and the engine came to disagree about what "8" means.
 */
class FullLoadCapacityTest {

    private final SyncProperties properties = new SyncProperties();

    private static DatabaseConfig db(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        return config;
    }

    @Test
    void distinctEndpointsSeatOneWorkerPerSpareConnection() {
        // Default pool of 5; the main cycle holds one connection per side for the whole load.
        assertThat(FullLoadCapacity.poolBound(properties, db(1L), db(2L))).isEqualTo(4);
    }

    @Test
    void aSharedConnectionSeatsHalfTheRemainder() {
        // Both sides use one pool: the cycle holds two connections, each worker borrows two.
        assertThat(FullLoadCapacity.poolBound(properties, db(1L), db(1L))).isEqualTo(1);
    }

    @Test
    void aMissingConfigFallsBackToTheDistinctSideRule() {
        assertThat(FullLoadCapacity.poolBound(properties, null, db(2L))).isEqualTo(4);
    }

    @Test
    void aTinyPoolStillSeatsOneWorker() {
        properties.setMaxPoolSize(1);
        assertThat(FullLoadCapacity.poolBound(properties, db(1L), db(2L))).isEqualTo(1);
    }

    @Test
    void theCeilingWinsOverAnOversizedPool() {
        properties.setMaxPoolSize(20);
        assertThat(FullLoadCapacity.poolBound(properties, db(1L), db(2L))).isEqualTo(19);
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L))).isEqualTo(8);
    }

    @Test
    void theCapFollowsAConfiguredPoolSize() {
        properties.setMaxPoolSize(3);
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L))).isEqualTo(2);
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(1L))).isEqualTo(1);
    }

    /**
     * The bound above assumes the pool serves one cycle. Once a second cycle is admitted on
     * the same configuration, each holds its own main connection, and the spare seats are
     * split between them: two workers in total, not four each.
     */
    @Test
    void cyclesSharingAConfigurationSplitItsSpareSeats() {
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L), 2, 2))
                .isEqualTo(1);
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L), 1, 2))
                .isEqualTo(1);
    }

    @Test
    void aLoneCycleKeepsTheStaticBound() {
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L), 1, 1))
                .isEqualTo(4);
        // Shared pool: the cycle's two connections are both seats of the same configuration.
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(1L), 2, 2))
                .isEqualTo(1);
    }

    @Test
    void anUntrackedConfigurationCountsAsOneSeat() {
        assertThat(FullLoadCapacity.effectiveCap(properties, db(1L), db(2L), 0, 0))
                .isEqualTo(4);
    }
}
