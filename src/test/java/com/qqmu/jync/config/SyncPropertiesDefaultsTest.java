package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The initial full load and the incremental/CDC replay share one code path but not one
 * budget. They must be tuned independently: raising the full-load batch to make a million-row
 * seed faster must not grow the transactions of a two-second incremental window or of a CDC
 * replay, which sit in front of live applications.
 */
class SyncPropertiesDefaultsTest {

    @Test
    void fullLoadKnobsAreSeparateFromTheIncrementalDefaults() {
        SyncProperties properties = new SyncProperties();

        assertThat(properties.getBatchSize()).isEqualTo(500);
        assertThat(properties.getFetchSize()).isEqualTo(1000);

        assertThat(properties.getFullLoadBatchSize()).isEqualTo(2000);
        assertThat(properties.getFullLoadFetchSize()).isEqualTo(2000);
        assertThat(properties.getFullLoadParallelism())
                .as("2 workers measured ~184k rows/s vs ~117k at 1; 4 added another ~18%")
                .isEqualTo(2);

        assertThat(properties.isOracleInitialLoadInsertFirst())
                .as("the INSERT-only initial load is the faster path and must be on by default")
                .isTrue();

        assertThat(properties.isDeferSecondaryIndexes())
                .as("building secondary indexes after the load halved its duration in the benchmark")
                .isTrue();
    }
}
