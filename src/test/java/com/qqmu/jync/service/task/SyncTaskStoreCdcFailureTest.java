package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.SyncTaskRepository;

/**
 * CDC 致命故障(purge/撞 id)必须**立即** ERROR —— 轮询路径的「连续失败 ≥ max-retries
 * 才 ERROR」对流式故障太迟钝:位点已不可用,重试一万次也没用,用户要马上在 UI 看到。
 * 搭法照抄 SyncTaskStoreStopTest(mock repository + 手建 SyncTask)。
 */
class SyncTaskStoreCdcFailureTest {

    private SyncTaskStore store;
    private SyncTask task;

    @BeforeEach
    void setUp() {
        SyncTaskRepository repository = mock(SyncTaskRepository.class);
        store = new SyncTaskStore(repository, new SyncProperties());
        task = new SyncTask();
        task.setProjectId(21L);
        task.setStatus(TaskStatus.STOPPED);
        task.setConsecutiveFailures(0);
        when(repository.findByProjectId(21L)).thenReturn(Optional.of(task));
    }

    @Test
    void recordCdcFailureSetsErrorImmediately() {
        store.recordCdcFailure(21L, "binlog purged");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ERROR);
        assertThat(task.getLastSyncResult()).contains("binlog purged");
        assertThat(task.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void recordCdcFailureFromErrorKeepsCounting() {
        store.recordCdcFailure(21L, "first");
        store.recordCdcFailure(21L, "second");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ERROR);
        assertThat(task.getConsecutiveFailures()).isEqualTo(2);
        assertThat(task.getLastSyncResult()).contains("second");
    }

    @Test
    void recordCdcFailureTruncatesLongMessages() {
        store.recordCdcFailure(21L, "x".repeat(5000));
        assertThat(task.getLastSyncResult().length()).isLessThanOrEqualTo(2003);
    }

    @Test
    void markCdcRunningClearsError() {
        store.recordCdcFailure(21L, "binlog purged");
        store.markCdcRunning(21L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void markCdcRunningClearsTheStaleResultSummary() {
        store.recordCdcFailure(21L, "SERVER_ID_CONFLICT: duplicate replica");
        store.markCdcRunning(21L);
        // 状态已经回到 RUNNING,详情页却还在展示上一次致命故障的原文 —— 会话恢复后
        // 这条摘要描述的是历史,不是现状。
        assertThat(task.getLastSyncResult()).isNull();
    }

    @Test
    void markCdcRunningClearsAStaleSummaryLeftOnAnAlreadyRunningTask() {
        task.setStatus(TaskStatus.RUNNING);
        task.setLastSyncResult("SERVER_ID_CONFLICT: duplicate replica");

        store.markCdcRunning(21L);

        assertThat(task.getLastSyncResult()).isNull();
    }
}
