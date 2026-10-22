package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Batches are limited by bytes, not rows: one 8 MB row and ten thousand 10-byte rows both
 * fit in a fixed row count badly, in opposite directions.
 */
class BatchByteBudgetTest {

    @Test
    void emptyBudgetAcceptsAnyRow() {
        BatchByteBudget budget = new BatchByteBudget(10);
        assertThat(budget.isEmpty()).isTrue();
        assertThat(budget.wouldExceed(100)).isFalse();
    }

    @Test
    void rowThatWouldOverflowAsksForAFlush() {
        BatchByteBudget budget = new BatchByteBudget(10);
        budget.add(6);
        assertThat(budget.wouldExceed(4)).isFalse();
        assertThat(budget.wouldExceed(5)).isTrue();
        assertThat(budget.rows()).isEqualTo(1);
        assertThat(budget.used()).isEqualTo(6);
    }

    @Test
    void resetStartsANewBatch() {
        BatchByteBudget budget = new BatchByteBudget(10);
        budget.add(9);
        budget.reset();
        assertThat(budget.isEmpty()).isTrue();
        assertThat(budget.used()).isZero();
        assertThat(budget.rows()).isZero();
    }

    @Test
    void sizeCountsTextInUtf8BytesAndBinaryAsItself() {
        assertThat(BatchByteBudget.sizeOf("中")).isEqualTo(3);
        assertThat(BatchByteBudget.sizeOf("abc")).isEqualTo(3);
        assertThat(BatchByteBudget.sizeOf(new byte[] {1, 2})).isEqualTo(2);
        assertThat(BatchByteBudget.sizeOf(null)).isZero();
        assertThat(BatchByteBudget.sizeOf(42)).isEqualTo(8);
    }

    @Test
    void sizeOfLobValueUsesItsLengthNotItsHeapFootprint() {
        LobValue text = LobValues.text("中文");
        // A LOB's length() is the best estimate we have without reading it again; for text
        // it is characters, which is the same order of magnitude as UTF-8 bytes.
        assertThat(BatchByteBudget.sizeOf(text)).isEqualTo(2);
        LobValue unknown = LobValues.oneShotText(new java.io.StringReader("x"), -1);
        assertThat(BatchByteBudget.sizeOf(unknown)).isEqualTo(0);
        unknown.release();
    }
}
