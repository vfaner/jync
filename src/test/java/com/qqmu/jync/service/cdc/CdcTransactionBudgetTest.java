package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CdcTransactionBudgetTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void aZeroLimitDisablesTheDimension() {
        CdcTransactionBudget budget = new CdcTransactionBudget(0, 0);
        assertThatCode(() -> {
            for (int i = 0; i < 10_000; i++) {
                budget.add(row("id", i));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void theRowCapTripsOnTheFirstRowOverTheLimit() {
        CdcTransactionBudget budget = new CdcTransactionBudget(2, 0);
        budget.add(row("id", 1));
        budget.add(row("id", 2));

        assertThatThrownBy(() -> budget.add(row("id", 3)))
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class)
                .hasMessageContaining(CdcTransactionBudget.TOO_LARGE_MARKER)
                .hasMessageContaining("3 row(s)")
                .hasMessageContaining("sync.cdc.max-transaction-rows");
    }

    @Test
    void theByteCapTripsOnWideValues() {
        CdcTransactionBudget budget = new CdcTransactionBudget(0, 100);
        budget.add(row("payload", "x".repeat(40)));   // 16 + 2*40 = 96 bytes

        assertThatThrownBy(() -> budget.add(row("payload", "y".repeat(40))))
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class)
                .hasMessageContaining("byte(s)");
    }

    /**
     * A savepoint copies the whole folded buffer, so a savepoint-per-row batch holds
     * O(depth x rows) snapshots. Those copies are live heap for as long as the transaction
     * runs and must count against the same cap, otherwise the guard misses exactly the
     * transactions that blow the heap up.
     */
    @Test
    void aSavepointSnapshotCountsTowardTheRowCap() {
        CdcTransactionBudget budget = new CdcTransactionBudget(3, 0);
        budget.add(row("id", 1));
        budget.add(row("id", 2));

        assertThatThrownBy(() -> budget.addSavepointSnapshot(2))
                .isInstanceOf(CdcTransactionBudget.CdcTransactionTooLargeException.class)
                .hasMessageContaining(CdcTransactionBudget.TOO_LARGE_MARKER)
                .hasMessageContaining("4 row(s)")
                .hasMessageContaining("savepoint")
                .hasMessageContaining("sync.cdc.max-transaction-rows");
    }

    @Test
    void aZeroRowLimitAlsoDisablesTheSavepointDimension() {
        CdcTransactionBudget budget = new CdcTransactionBudget(0, 0);
        assertThatCode(() -> budget.addSavepointSnapshot(10_000)).doesNotThrowAnyException();
    }

    @Test
    void resetStartsTheNextTransactionFromZero() {
        CdcTransactionBudget budget = new CdcTransactionBudget(1, 0);
        budget.add(row("id", 1));
        budget.reset();

        assertThatCode(() -> budget.add(row("id", 2))).doesNotThrowAnyException();
    }

    @Test
    void estimateCountsTextBinaryAndScalars() {
        assertThat(CdcTransactionBudget.estimateBytes(row("s", "abcd"))).isEqualTo(24L);
        assertThat(CdcTransactionBudget.estimateBytes(row("b", new byte[]{1, 2, 3})))
                .isEqualTo(19L);
        assertThat(CdcTransactionBudget.estimateBytes(row("n", 42L))).isEqualTo(48L);
    }
}
