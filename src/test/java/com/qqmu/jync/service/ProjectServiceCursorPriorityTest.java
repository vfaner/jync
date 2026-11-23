package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.projection.redis.RedisProjectionPolicy;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/** Project-level cursor priority is configuration-only; progress is re-baselined lazily. */
@ExtendWith(MockitoExtension.class)
class ProjectServiceCursorPriorityTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private com.qqmu.jync.repository.ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private CdcPositionStore positionStore;
    @Mock private com.qqmu.jync.repository.TaskMetricSampleRepository taskMetricSampleRepository;
    @Mock private com.qqmu.jync.repository.AlertStateRepository alertStateRepository;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                taskMetricSampleRepository, alertStateRepository, new SyncProperties(),
                mock(RedisProjectionPolicy.class));
    }

    private Project project(boolean enabled) {
        Project p = new Project();
        p.setId(1L);
        p.setName("proj");
        p.setEnabled(enabled);
        return p;
    }

    private void stoppedProjectWith(Project project, SyncConfig config) {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.parseConfig(project)).thenReturn(config);
        when(taskStore.find(1L)).thenReturn(Optional.empty());
    }

    @Test
    void aRunningProjectRefusesTheChange() {
        Project running = project(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(running));
        lenient().when(contextFactory.parseConfig(running)).thenReturn(new SyncConfig());

        assertThatThrownBy(() -> service.saveCursorPriority(1L,
                List.of("update_stamp"), AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.stop.before.config");

        verify(projectRepository, never()).save(any());
        verify(stateWriter, never()).clearCursor(anyLong(), anyString());
    }

    @Test
    void savesTrimmedDeduplicatedPriorityWithoutTouchingCursors() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        stoppedProjectWith(stopped, config);
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveCursorPriority(1L,
                List.of(" update_stamp ", "UPDATE_STAMP", "update_time", " "),
                AccessGuard.system());

        assertThat(config.getCursorColumnPriority())
                .containsExactly("update_stamp", "update_time");
        verify(projectRepository).save(stopped);
        // Re-baselining is lazy in SyncEngine: saving a 700-table project must not scan
        // every table or clear every cursor here.
        verify(stateWriter, never()).clearCursor(anyLong(), anyString());
    }

    @Test
    void anUnchangedPriorityIsANoOp() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.setCursorColumnPriority(List.of("update_stamp"));
        stoppedProjectWith(stopped, config);

        service.saveCursorPriority(1L, List.of(" UPDATE_STAMP "), AccessGuard.system());

        verify(projectRepository, never()).save(any());
        verify(stateWriter, never()).clearCursor(anyLong(), anyString());
    }

    @Test
    void fullCompareProjectsRefuseACursorPriority() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.FULL_COMPARE);
        stoppedProjectWith(stopped, config);

        assertThatThrownBy(() -> service.saveCursorPriority(1L,
                List.of("update_stamp"), AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.cursor.priority.cdc");

        verify(projectRepository, never()).save(any());
    }

    @Test
    void logCdcProjectsRefuseACursorPriority() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        stoppedProjectWith(stopped, config);

        assertThatThrownBy(() -> service.saveCursorPriority(1L,
                List.of("update_stamp"), AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.cursor.priority.cdc");

        verify(projectRepository, never()).save(any());
    }
}
