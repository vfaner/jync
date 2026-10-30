package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;

/**
 * Full-load parallelism is resolved once, in the factory, where both the project's setting
 * and the connection pool are known. Getting it wrong here does not fail a test at runtime —
 * workers just queue on the pool, or ask for connections that do not exist — so the caps are
 * pinned: a project may lower the global value but never exceed 8, and the pool must still
 * hold the main cycle's connection plus one source and one target connection per worker.
 */
class SyncContextFactoryParallelismTest {

    private final SyncProperties properties = new SyncProperties();
    private final PoolActivity poolActivity = new PoolActivity(properties);
    private final SyncContextFactory factory =
            new SyncContextFactory(null, null, null, null, null, properties, null, poolActivity);

    @Test
    void projectValueOverridesTheGlobalDefault() {
        properties.setFullLoadParallelism(2);
        SyncConfig config = new SyncConfig();
        config.setFullLoadParallelism(3);

        assertThat(factory.resolveFullLoadParallelism(config, db(1L), db(2L))).isEqualTo(3);
    }

    @Test
    void absentProjectValueFallsBackToTheGlobalDefault() {
        properties.setFullLoadParallelism(2);
        properties.setMaxPoolSize(8);

        assertThat(factory.resolveFullLoadParallelism(new SyncConfig(), db(1L), db(2L)))
                .isEqualTo(2);
    }

    @Test
    void parallelismNeverExceedsEight() {
        SyncConfig config = new SyncConfig();
        config.setFullLoadParallelism(99);
        properties.setMaxPoolSize(20);

        assertThat(factory.resolveFullLoadParallelism(config, db(1L), db(2L))).isEqualTo(8);
    }

    @Test
    void workersStopAtThePoolSizeWhenSourceAndTargetAreDifferentDatabases() {
        // Pool of 5: one connection is held by the main cycle, leaving 4 for workers.
        properties.setMaxPoolSize(5);
        SyncConfig config = new SyncConfig();
        config.setFullLoadParallelism(6);

        assertThat(factory.resolveFullLoadParallelism(config, db(1L), db(2L))).isEqualTo(4);
    }

    @Test
    void oneDatabaseServingBothSidesHalvesTheBudget() {
        // A single pool of 5 holds the main cycle's source AND target connection (2), and
        // each worker borrows one of each (2 per worker), leaving (5-2)/2 = 1.
        properties.setMaxPoolSize(5);
        SyncConfig config = new SyncConfig();
        config.setFullLoadParallelism(4);

        assertThat(factory.resolveFullLoadParallelism(config, db(7L), db(7L))).isEqualTo(1);
    }

    /**
     * The project asks for 4 workers, but another cycle already holds a connection on the
     * source: the two cycles share the pool's spare seats, so each gets 1 — the old static
     * cap kept handing out 4 and let the workers fight over what was left.
     */
    @Test
    void aSecondCycleOnTheSameConfigurationSplitsTheSpareSeats() {
        properties.setMaxPoolSize(5);
        SyncConfig config = new SyncConfig();
        config.setFullLoadParallelism(4);
        poolActivity.tryEnter(1L, 99L);
        poolActivity.tryEnter(1L, 98L);

        assertThat(factory.resolveFullLoadParallelism(config, db(1L), db(2L))).isEqualTo(1);
    }

    @Test
    void zeroOrNegativeProjectValuesFallBackToTheGlobalDefault() {
        properties.setFullLoadParallelism(3);
        SyncConfig zero = new SyncConfig();
        zero.setFullLoadParallelism(0);
        SyncConfig negative = new SyncConfig();
        negative.setFullLoadParallelism(-2);

        assertThat(factory.resolveFullLoadParallelism(zero, db(1L), db(2L))).isEqualTo(3);
        assertThat(factory.resolveFullLoadParallelism(negative, db(1L), db(2L))).isEqualTo(3);
    }

    @Test
    void fullLoadBatchSizeFallsBackToTheFullLoadDefaultNotTheIncrementalOne() {
        properties.setBatchSize(500);
        properties.setFullLoadBatchSize(2000);

        assertThat(factory.resolveFullLoadBatchSize(new SyncConfig())).isEqualTo(2000);
    }

    @Test
    void fullLoadBatchSizeFromTheProjectIsClampedToTheSupportedRange() {
        properties.setFullLoadBatchSize(2000);
        SyncConfig huge = new SyncConfig();
        huge.setBatchSize(99_999);
        SyncConfig tiny = new SyncConfig();
        tiny.setBatchSize(0);

        assertThat(factory.resolveFullLoadBatchSize(huge)).isEqualTo(20_000);
        assertThat(factory.resolveFullLoadBatchSize(tiny)).isEqualTo(2000);
    }

    @Test
    void fullLoadFetchSizeComesFromTheFullLoadKnobAndIsClamped() {
        properties.setFetchSize(1000);
        properties.setFullLoadFetchSize(2000);
        assertThat(factory.resolveFullLoadFetchSize()).isEqualTo(2000);

        properties.setFullLoadFetchSize(0);
        assertThat(factory.resolveFullLoadFetchSize()).isEqualTo(1);
    }

    private static DatabaseConfig db(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        return config;
    }
}
