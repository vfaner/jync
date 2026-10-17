package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * 聚合变更日志的落库契约:窗口到龄才写、一张表一条(ObjectType.DATA + ChangeType.INFO,
 * 行数=upsert+delete),明细带 upsert/delete 拆分与「截至位点」;未到龄不写;
 * close 兜底把剩余窗口全部落库;元数据库写失败只 warn 不外抛(消费线程不能被
 * 可观测性旁路带崩)。
 */
class CdcChangeWindowLoggerTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final SyncStateWriter stateWriter = mock(SyncStateWriter.class);
    private final SyncTaskStore taskStore = mock(SyncTaskStore.class);

    private CdcChangeWindowLogger logger(CdcRuntimeStats stats, long windowMs) {
        return new CdcChangeWindowLogger(7L, stats, stateWriter, taskStore, windowMs,
                1_000L, clock::get);
    }

    @Test
    void dueWindowWritesOneAggregatedLogPerTableWithPositionLabel() {
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.recordPositionScn(2_112_284L);
        stats.addTableUpserts("ORDERS", 123);
        stats.addTableDeletes("ORDERS", 4);
        CdcChangeWindowLogger logger = logger(stats, 10_000L);

        clock.set(5_000L);
        logger.flushDue();   // 窗口只活了 4s
        verify(stateWriter, never()).recordLog(anyLong(), any(), any(), any(),
                any(), anyBoolean(), anyLong(), any());

        clock.set(11_000L);
        logger.flushDue();

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> rows = ArgumentCaptor.forClass(Integer.class);
        verify(stateWriter).recordLog(eq(7L), eq(ObjectType.DATA), eq("ORDERS"),
                eq(ChangeType.INFO), detail.capture(), eq(true), eq(0L), rows.capture());
        assertThat(detail.getValue()).contains("123 upserts", "4 deletes",
                "through SCN 2112284");
        assertThat(rows.getValue()).isEqualTo(127);

        // 已交接:到龄再刷不重复写
        clock.set(20_000L);
        logger.flushDue();
        verify(stateWriter, org.mockito.Mockito.times(1)).recordLog(anyLong(), any(),
                any(), any(), any(), anyBoolean(), anyLong(), any());
    }

    @Test
    void closeFlushesRemainingUndueWindows() {
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.recordPosition("mysql-bin.000003", 154L, null);
        stats.addTableUpserts("AUDIT", 9);
        CdcChangeWindowLogger logger = logger(stats, 10_000L);

        logger.close();   // 不推进时钟:未到龄也必须在停机时落库

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(stateWriter).recordLog(eq(7L), eq(ObjectType.DATA), eq("AUDIT"),
                eq(ChangeType.INFO), detail.capture(), eq(true), eq(0L), eq(9));
        assertThat(detail.getValue()).contains("9 upserts", "through mysql-bin.000003:154");
    }

    @Test
    void aFailingLogWriteDoesNotEscape() {
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.addTableDeletes("ORDERS", 2);
        doThrow(new RuntimeException("meta DB down")).when(stateWriter)
                .recordLog(anyLong(), any(), any(), any(), any(),
                        anyBoolean(), anyLong(), any());
        CdcChangeWindowLogger logger = logger(stats, 10_000L);
        clock.set(20_000L);

        assertThatCode(logger::flushDue).doesNotThrowAnyException();
    }

    @Test
    void noPositionMeansNoThroughClause() {
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.addTableUpserts("T", 1);
        CdcChangeWindowLogger logger = logger(stats, 10_000L);
        clock.set(20_000L);

        logger.flushDue();

        verify(stateWriter).recordLog(eq(7L), eq(ObjectType.DATA), eq("T"),
                eq(ChangeType.INFO), contains("1 upserts"), eq(true), eq(0L), eq(1));
    }

    @Test
    void aWindowWithAppliedRowsAdvancesTheLastSyncTimeOncePerFlush() {
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.addTableUpserts("ORDERS", 3);
        stats.addTableDeletes("ORDERS", 1);
        stats.addTableUpserts("AUDIT", 2);   // 两表同批落库,只推进一次
        CdcChangeWindowLogger logger = logger(stats, 10_000L);
        clock.set(20_000L);

        logger.flushDue();

        verify(taskStore, times(1)).markCdcDataApplied(7L);
    }

    @Test
    void anIdleTickDoesNotTouchTheTask() {
        CdcChangeWindowLogger logger = logger(new CdcRuntimeStats(clock::get), 10_000L);
        clock.set(20_000L);

        logger.flushDue();

        verify(taskStore, never()).markCdcDataApplied(anyLong());
    }

    @Test
    void aFailingLogWriteDoesNotSuppressTheTimestamp() {
        // 数据已经落进目标库,变更日志写失败只是少了审计记录,不能连带瞒掉「上次同步」。
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.addTableDeletes("ORDERS", 2);
        doThrow(new RuntimeException("meta DB down")).when(stateWriter)
                .recordLog(anyLong(), any(), any(), any(), any(),
                        anyBoolean(), anyLong(), any());
        CdcChangeWindowLogger logger = logger(stats, 10_000L);
        clock.set(20_000L);

        logger.flushDue();

        verify(taskStore).markCdcDataApplied(7L);
    }

    @Test
    void aFailingTaskWriteDoesNotEscape() {
        // 时间戳写入同样是旁路:元数据库不可用时消费线程不能被它带崩。
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.addTableUpserts("ORDERS", 1);
        doThrow(new RuntimeException("meta DB down")).when(taskStore)
                .markCdcDataApplied(anyLong());
        CdcChangeWindowLogger logger = logger(stats, 10_000L);
        clock.set(20_000L);

        assertThatCode(logger::flushDue).doesNotThrowAnyException();
    }
}
