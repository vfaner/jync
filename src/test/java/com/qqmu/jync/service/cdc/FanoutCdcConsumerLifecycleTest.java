package com.qqmu.jync.service.cdc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.fanout.FanoutRuntimeFactory;
import com.qqmu.jync.service.fanout.FanoutRuntimeRegistry;
import com.qqmu.jync.service.sync.AutoSyncService;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The fan-out consumer must release its daemon lease even when it fails before a runtime
 * exists. Otherwise the lease is renewed forever and the per-project semaphore stays at
 * zero, wedging every later start on this node.
 */
class FanoutCdcConsumerLifecycleTest {

    @Test
    void aPreludeFailureStillClosesTheDaemonLock() throws Exception {
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        FanoutRuntimeFactory runtimes = mock(FanoutRuntimeFactory.class);
        when(runtimes.create(any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("metadata unavailable"));
        Project project = new Project();
        project.setId(9L);
        project.setName("fanout");

        FanoutCdcConsumer consumer = new FanoutCdcConsumer(project, lock,
                mock(SyncContext.class), mock(BinlogClientFactory.class),
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(CdcRuntimeStats.class),
                new SyncProperties(), runtimes, mock(FanoutRuntimeRegistry.class),
                mock(AutoSyncService.class), mock(SyncStateWriter.class));

        consumer.run();

        verify(lock).close();
    }

    @Test
    void aNewTableDdlMovesTheSharedAnchorPastItsNextPosition() {
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        CdcPositionStore positions = mock(CdcPositionStore.class);
        Project project = new Project();
        project.setId(9L);
        project.setName("fanout");
        FanoutCdcConsumer consumer = new FanoutCdcConsumer(project, lock,
                mock(SyncContext.class), mock(BinlogClientFactory.class), positions,
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(CdcRuntimeStats.class), new SyncProperties(),
                mock(FanoutRuntimeFactory.class), mock(FanoutRuntimeRegistry.class),
                mock(AutoSyncService.class), mock(SyncStateWriter.class));
        BinaryLogClient client = mock(BinaryLogClient.class);
        when(client.getGtidSet()).thenReturn("gtid-set");

        consumer.advancePastNewTable("mysql-bin.000001", 123L, client);

        verify(positions).advance(9L, "mysql-bin.000001", 123L, "gtid-set");
    }
}
