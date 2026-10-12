package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * LogMinerCdcConsumer lifecycle tests with a scripted fake session. Mirrors CdcConsumerTest:
 * lease-loss quiet exit, requestStop mid-cycle, missing-SCN fatal park.
 */
class LogMinerCdcConsumerTest {

    private static Project project(long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("lm-" + id);
        return p;
    }

    /** Fake session that returns a scripted list of batches, then drains. */
    private static final class FakeSession extends LogMinerSession {
        final List<List<LogMinerSession.Row>> batches;
        int closed;

        @SafeVarargs
        FakeSession(List<LogMinerSession.Row>... batches) {
            super(null, 0L, 0L, null, null);
            this.batches = new ArrayList<>(java.util.Arrays.asList(batches));
        }

        @Override
        public List<LogMinerSession.Row> nextBatch() {
            if (batches.isEmpty()) {
                return Collections.emptyList();
            }
            return batches.remove(0);
        }

        @Override
        public void close() {
            closed++;
        }
    }

    private static CdcPosition scnPosition(long projectId, long scn) {
        CdcPosition p = new CdcPosition();
        p.setProjectId(projectId);
        p.setScn(scn);
        p.setBaselineDone(true);
        return p;
    }

    @Test
    void leaseLossExitsQuietlyWithoutError() throws Exception {
        Project project = project(7L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(false);
        SyncContext ctx = SyncContext.builder().project(project)
                .config(new SyncConfig()).build();
        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        when(positionStore.find(7L)).thenReturn(Optional.of(scnPosition(7L, 500L)));
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        LogMinerSessionFactory factory = (c, s, b) -> {
            throw new UnsupportedOperationException("no session needed");
        };

        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties(), factory);

        Thread t = new Thread(consumer, "lm-test-7");
        t.start();
        t.join(5_000);

        assertThat(t.isAlive()).isFalse();
        verify(baselineService, never()).runBaseline(any(), any());
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(),
                eq(ChangeType.ERROR), anyString(), anyBoolean(), anyLong());
        verify(stateWriter).recordLog(eq(7L), eq(ObjectType.PROJECT), eq("lm-7"),
                eq(ChangeType.INFO), eq("LogMiner consumer stopped: lease lost"),
                eq(true), eq(0L));
        verify(lock).close();
    }

    @Test
    void missingScnAfterBaselineParksInError() throws Exception {
        Project project = project(8L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);
        SyncContext ctx = SyncContext.builder().project(project)
                .config(new SyncConfig()).build();
        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        // Baseline completed but no SCN recorded (null) — must refuse to mine.
        CdcPosition noScn = new CdcPosition();
        noScn.setProjectId(8L);
        noScn.setBaselineDone(true);
        noScn.setScn(null);
        when(positionStore.find(8L)).thenReturn(Optional.of(noScn));
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        LogMinerSessionFactory factory = (c, s, b) -> {
            throw new UnsupportedOperationException();
        };

        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties(), factory);

        Thread t = new Thread(consumer);
        t.start();
        t.join(5_000);

        assertThat(t.isAlive()).isFalse();
        verify(taskStore).recordCdcFailure(eq(8L), anyString());
        verify(stateWriter).recordLog(eq(8L), eq(ObjectType.PROJECT), eq("lm-8"),
                eq(ChangeType.ERROR), anyString(), eq(false), eq(0L));
    }

    @Test
    void requestStopMidCycleQuietExits() throws Exception {
        Project project = project(9L);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenReturn(true);
        SyncConfig cfg = new SyncConfig();
        SyncContext ctx = SyncContext.builder().project(project).config(cfg).build();
        CdcPositionStore positionStore = mock(CdcPositionStore.class);
        when(positionStore.find(9L)).thenReturn(Optional.of(scnPosition(9L, 100L)));
        CdcBaselineService baselineService = mock(CdcBaselineService.class);
        SyncTaskStore taskStore = mock(SyncTaskStore.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        DataSourceManager dataSourceManager = mock(DataSourceManager.class);

        FakeSession session = new FakeSession(Collections.emptyList());
        LogMinerSessionFactory factory = (c, s, b) -> session;
        Connection sourceConn = mock(Connection.class);
        when(dataSourceManager.getConnection(any())).thenReturn(sourceConn);

        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, lock, ctx,
                positionStore, baselineService, taskStore, stateWriter, dataSourceManager,
                new CdcRuntimeStats(), new SyncProperties(), factory);

        Thread t = new Thread(consumer);
        t.start();
        Thread.sleep(300);
        consumer.requestStop();
        t.join(5_000);

        assertThat(t.isAlive()).isFalse();
        verify(taskStore, never()).recordCdcFailure(anyLong(), any());
        verify(lock).close();
    }
}
