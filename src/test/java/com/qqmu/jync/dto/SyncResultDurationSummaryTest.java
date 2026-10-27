package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 任务列表与任务详情的"详情"一行直接展示 {@link SyncResult#summary()}。耗时是这里唯一的
 * 数值型尾巴：100705ms 这种写法既占地方又要求用户自己换算。
 */
class SyncResultDurationSummaryTest {

    @Test
    void theSummaryReadsTheDurationInHumanUnits() {
        SyncResult result = new SyncResult();
        result.setStructureChanges(5);
        result.setRowsInserted(10_100_327);
        result.setTablesProcessed(5);
        result.setDurationMs(100_705);

        assertThat(result.summary())
                .isEqualTo("DDL=5, rows +10100327 ~0 -0, tables=5, 1m 40s");
    }

    /**
     * 一张几十亿行的表做全量基线时,单轮计数就会越过 int 上限;int 字段会翻成负数,
     * 任务列表显示 "rows +-1294967296",失败判定也跟着错。计数必须是 long。
     */
    @Test
    void rowCountsBeyondIntRangeAreNotTruncated() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(3_000_000_000L);
        result.setRowsUpdated(2_000_000_000L);
        result.setRowsDeleted(1_500_000_000L);

        assertThat(result.totalRows()).isEqualTo(6_500_000_000L);
        assertThat(result.hasChanges()).isTrue();
        assertThat(result.summary())
                .contains("rows +3000000000 ~2000000000 -1500000000");
    }

    @Test
    void aShortRunKeepsMilliseconds() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(3);
        result.setTablesProcessed(1);
        result.setDurationMs(412);

        assertThat(result.summary()).endsWith("412 ms");
    }
}
