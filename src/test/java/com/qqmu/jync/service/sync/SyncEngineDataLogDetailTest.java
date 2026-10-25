package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The scan detail attached to a data change-log entry.
 *
 * <p>An initial load that re-reads a table it has already partly seeded reports fewer
 * inserted rows than it scanned — the rest were already present with identical values and
 * the upsert changed nothing. Without saying so, "50 row(s) inserted (53 scanned)" next to
 * a 53-row target reads like a miscount. The clause is initial-load only: full-compare
 * cycles and safety-margin replays rescans whole tables or margin windows by design, where
 * the same number would be per-cycle noise.
 */
class SyncEngineDataLogDetailTest {

    @Test
    void anInitialLoadNamesTheRowsThatWereAlreadyUpToDate() {
        assertThat(SyncEngine.scanDetail(50, 50, 53, true))
                .isEqualTo(" (53 scanned, 3 already up to date)");
    }

    @Test
    void anInitialLoadWhoseScannedRowsAllChangedKeepsThePlainSuffix() {
        // Inserted 50 and updated 3 in one load: the two log entries already add up to 53.
        assertThat(SyncEngine.scanDetail(50, 53, 53, true))
                .isEqualTo(" (53 scanned)");
    }

    @Test
    void anIncrementalWindowDoesNotAddTheClause() {
        assertThat(SyncEngine.scanDetail(1, 1, 3, false))
                .isEqualTo(" (3 scanned)");
    }

    @Test
    void aTableThatOnlyChangedRowsHasNoSuffixAtAll() {
        assertThat(SyncEngine.scanDetail(50, 50, 50, true)).isEmpty();
    }
}
