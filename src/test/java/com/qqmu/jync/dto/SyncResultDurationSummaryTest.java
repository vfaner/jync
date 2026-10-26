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

    @Test
    void aShortRunKeepsMilliseconds() {
        SyncResult result = new SyncResult();
        result.setRowsInserted(3);
        result.setTablesProcessed(1);
        result.setDurationMs(412);

        assertThat(result.summary()).endsWith("412 ms");
    }
}
