package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * MySQL Connector/J with {@code rewriteBatchedStatements=true} collapses a batch into one
 * multi-row statement and then answers {@link Statement#SUCCESS_NO_INFO} for every row of the
 * batch — including the rows whose values did not change. Counting each such row as changed
 * reported a whole batch as updated when a single row moved (a full-compare cycle on a MySQL
 * target was the loud case).
 *
 * <p>Right after the rewritten statement, MySQL's own {@code ROW_COUNT()} still knows the
 * batch's true affected-rows total (0 when nothing changed), which bounds the count: one row
 * contributes at most one changed row, and an updated row contributes 2 to that total. The
 * upper bound is the honest number the change log can use — it never invents rows that were
 * not in the batch.
 */
class RewrittenBatchCountTest {

    private static final int SNI = Statement.SUCCESS_NO_INFO;

    @Test
    void aRewrittenBatchIsBoundedByItsAffectedRows() {
        int[] counts = {SNI, SNI, SNI};

        // 1 update (2) + 2 unchanged (0): the batch has three rows, so three is the cap.
        assertThat(DataSyncService.rewrittenBatchChangedRows(counts, 2L)).isEqualTo(2);
        // All inserts: affected == rows.
        assertThat(DataSyncService.rewrittenBatchChangedRows(counts, 3L)).isEqualTo(3);
        // A batch can never report more changed rows than it holds (2 per update).
        assertThat(DataSyncService.rewrittenBatchChangedRows(counts, 6L)).isEqualTo(3);
        // Nothing moved.
        assertThat(DataSyncService.rewrittenBatchChangedRows(counts, 0L)).isZero();
    }

    @Test
    void aBatchWithRealPerRowCountsIsNotTreatedAsRewritten() {
        assertThat(DataSyncService.rewrittenBatchChangedRows(new int[] {0, 1, 1}, 2L))
                .isEqualTo(-1);
        assertThat(DataSyncService.rewrittenBatchChangedRows(new int[] {SNI, 1}, 2L))
                .isEqualTo(-1);
        assertThat(DataSyncService.rewrittenBatchChangedRows(new int[0], 2L)).isEqualTo(-1);
        assertThat(DataSyncService.rewrittenBatchChangedRows(null, 2L)).isEqualTo(-1);
    }

    @Test
    void anUnreadableRowCountFallsBackToPerRowCounting() {
        assertThat(DataSyncService.rewrittenBatchChangedRows(new int[] {SNI, SNI}, -1L))
                .isEqualTo(-1);
    }
}
