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
}
