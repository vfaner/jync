package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Out-of-line LOBs are the one LogMiner limitation a user must see: the row is skipped (or
 * the project parks), so the target silently lags behind the source. The counter is global
 * for the status panel and per table for the throttled warning — a table with a LOB column
 * updated every second must not write 86400 warnings a day.
 */
class CdcRuntimeStatsMissingLobTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);

    @Test
    void everySkippedRowIsCountedGloballyAndPerTable() {
        stats.recordMissingLob("SRC.OG_LOB", 60_000L);
        stats.recordMissingLob("SRC.OG_LOB", 60_000L);
        stats.recordMissingLob("SRC.OTHER", 60_000L);

        assertThat(stats.getSkippedMissingLob()).isEqualTo(3);
        assertThat(stats.missingLobByTable())
                .containsEntry("SRC.OG_LOB", 2L)
                .containsEntry("SRC.OTHER", 1L);
    }

    @Test
    void theWarningIsDueOncePerIntervalPerTable() {
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 60_000L).warnDue()).isTrue();
        clock.addAndGet(30_000L);
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 60_000L).warnDue()).isFalse();
        // A different table has its own clock.
        assertThat(stats.recordMissingLob("SRC.OTHER", 60_000L).warnDue()).isTrue();
        clock.addAndGet(30_001L);
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 60_000L).warnDue()).isTrue();
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 60_000L).count()).isEqualTo(4);
    }

    @Test
    void aZeroIntervalWarnsEveryTime() {
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 0L).warnDue()).isTrue();
        assertThat(stats.recordMissingLob("SRC.OG_LOB", 0L).warnDue()).isTrue();
    }

    @Test
    void theSnapshotCarriesTheCounterAndPerTableTallies() {
        stats.recordMissingLob("SRC.OG_LOB", 60_000L);

        Map<String, Object> snapshot = stats.snapshot();

        assertThat(snapshot).containsEntry("skippedMissingLob", 1L);
        assertThat(snapshot.get("missingLobByTable"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("SRC.OG_LOB", 1L);
    }
}
