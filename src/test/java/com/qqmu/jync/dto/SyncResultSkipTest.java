package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Skipped tables are reported separately from errors: a keyless table standing still is an
 * expected outcome, so it must neither flip {@code success} nor disappear from the summary.
 */
class SyncResultSkipTest {

    @Test
    void aSkippedTableDoesNotFailTheRun() {
        SyncResult result = new SyncResult();
        result.addSkipped("LOGS", "no incremental cursor column available");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getSkippedTables())
                .extracting(SyncResult.SkippedTable::table, SyncResult.SkippedTable::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "LOGS", "no incremental cursor column available"));
    }

    @Test
    void aRunWithOnlySkipsSaysSoInTheSummary() {
        SyncResult result = new SyncResult();
        result.addSkipped("A", "reason one");
        result.addSkipped("B", "reason two");

        assertThat(result.summary()).isEqualTo("No changes, skipped 2 table(s)");
    }

    @Test
    void skipsAreAppendedToANonEmptySummary() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(5);
        result.setTablesProcessed(1);
        result.addSkipped("B", "keyless");

        assertThat(result.summary()).contains("rows +5").contains("skipped=1");
    }

    @Test
    void anErrorStillTakesPrecedenceInTheSummary() {
        SyncResult result = new SyncResult();
        result.addSkipped("A", "reason");
        result.addError("boom");

        assertThat(result.summary()).startsWith("FAILED:").contains("boom");
    }
}
