package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.connection.PoolContentionException;
import com.qqmu.jync.service.monitor.ChangeDetector;

/**
 * A cycle that cannot borrow its two connections because another task's workers hold the
 * pool is deferred, not failed: nothing was attempted, no error row is written, and the next
 * scheduled fire tries again. A database that is actually unreachable must still fail.
 */
class SyncEnginePoolContentionTest {

    private final SyncProperties properties = new SyncProperties();
    private DataSourceManager dataSourceManager;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;
    private SyncContext ctx;

    @BeforeEach
    void setUp() {
        dataSourceManager = mock(DataSourceManager.class);
        stateWriter = mock(SyncStateWriter.class);
        engine = new SyncEngine(dataSourceManager, mock(ChangeDetector.class),
                new com.qqmu.jync.service.monitor.CursorStrategyResolver(properties),
                mock(StructureSyncService.class), mock(DataSyncService.class),
                stateWriter, properties);

        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        Project project = new Project();
        project.setId(9L);
        project.setName("orders");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(source)
                .targetConfig(target)
                .build();
    }

    @Test
    void aBusyPoolDefersTheCycleWithoutRecordingAFailure() throws SQLException {
        when(dataSourceManager.getConnectionOrDefer(any()))
                .thenThrow(new PoolContentionException("pool busy"));

        SyncResult result = engine.runCycle(ctx);

        assertThat(result.isDeferred()).isTrue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        verifyNoInteractions(stateWriter);
    }

    @Test
    void aRealConnectionFailureStillFailsTheCycle() throws SQLException {
        when(dataSourceManager.getConnectionOrDefer(any()))
                .thenThrow(new SQLTransientConnectionException("Connection refused"));

        SyncResult result = engine.runCycle(ctx);

        assertThat(result.isDeferred()).isFalse();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        verify(stateWriter).recordLog(any(), any(), any(), any(), any(), anyBoolean(),
                anyLong());
    }
}
