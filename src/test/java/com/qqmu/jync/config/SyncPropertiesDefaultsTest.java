package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

        assertThat(properties.isInitialLoadInsertFirst())
                .as("the INSERT-only initial load is the faster path and must be on by default")
                .isTrue();

        assertThat(properties.isDeferSecondaryIndexes())
                .as("building secondary indexes after the load halved its duration in the benchmark")
                .isTrue();
    }

    /**
     * The switch was Oracle-only in 1.7.6/1.7.7, so an install may still carry the old key in
     * its YAML. Renaming it must not silently re-enable a path the operator turned off.
     */
    @Test
    void theOldOracleOnlyKeyStillBindsAsAnAlias() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("sync.oracle-initial-load-insert-first=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SyncProperties.class).isInitialLoadInsertFirst())
                            .isFalse();
                });
    }

    @Test
    void theRenamedKeyBindsToo() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("sync.initial-load-insert-first=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SyncProperties.class).isInitialLoadInsertFirst())
                            .isFalse();
                });
    }

    @EnableConfigurationProperties(SyncProperties.class)
    static class PropertiesConfig {
    }
}
