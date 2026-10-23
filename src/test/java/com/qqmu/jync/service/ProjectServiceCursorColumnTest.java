package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * Changing a table's explicit cursor column re-baselines that table.
 *
 * <p>The stored watermark was produced by the OLD column. Feeding it to a different column
 * compares values from two different domains, so rows whose new-column value sits below the
 * old watermark would never be delivered. The fix is to drop that table's cursor and let the
 * next cycle reload it, exactly like {@code resetProgress} does for every table. A running
 * project refuses the change outright, like every other configuration edit.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceCursorColumnTest {

    private static final String TABLE = "orders";

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
    @Mock private com.qqmu.jync.service.cdc.CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private com.qqmu.jync.service.cdc.CdcPositionStore positionStore;

    private final com.qqmu.jync.config.SyncProperties properties =
            new com.qqmu.jync.config.SyncProperties();

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                properties);
    }

    private Project project(boolean enabled) {
        Project p = new Project();
        p.setId(1L);
        p.setName("proj");
        p.setEnabled(enabled);
        return p;
    }

    /** Minimal stubbing for a stopped project whose config has been loaded. */
    private void stoppedProjectWith(Project project, SyncConfig config) {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.parseConfig(project)).thenReturn(config);
        when(taskStore.find(1L)).thenReturn(Optional.empty());
    }

    @Test
    void aRunningProjectRefusesACursorColumnChange() {
        Project running = project(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(running));
        // Only reached if the guard is missing; lenient so the fixed code need not read it.
        lenient().when(contextFactory.parseConfig(running)).thenReturn(new SyncConfig());

        assertThatThrownBy(() -> service.saveCursorColumn(1L, TABLE, "created_at"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.stop.before.config");

        // Nothing persisted, nothing re-baselined: the in-flight cycle keeps its column.
        verify(projectRepository, never()).save(any());
        verify(stateWriter, never()).clearCursor(anyLong(), anyString());
    }

    @Test
    void changingTheColumnRebaselinesThatTable() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.getCursorColumns().put(TABLE, "updated_at");
        stoppedProjectWith(stopped, config);
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveCursorColumn(1L, TABLE, "created_at");

        assertThat(config.cursorColumnFor(TABLE)).isEqualTo("created_at");
        verify(projectRepository).save(stopped);
        verify(stateWriter).clearCursor(1L, TABLE);
    }

    @Test
    void anUnchangedColumnIsANoOp() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.getCursorColumns().put(TABLE, "updated_at");
        stoppedProjectWith(stopped, config);

        // Same value, only whitespace differs: no write and no needless full reload.
        service.saveCursorColumn(1L, TABLE, "  updated_at  ");

        verify(projectRepository, never()).save(any());
        verify(stateWriter, never()).clearCursor(anyLong(), anyString());
    }

    @Test
    void clearingTheColumnAlsoRebaselinesTheTable() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.getCursorColumns().put(TABLE, "updated_at");
        stoppedProjectWith(stopped, config);
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveCursorColumn(1L, TABLE, "   ");

        assertThat(config.getCursorColumns()).isEmpty();
        verify(stateWriter).clearCursor(1L, TABLE);
    }

    @Test
    void aDifferentlyCasedTableKeyIsReplacedNotDuplicated() {
        Project stopped = project(false);
        SyncConfig config = new SyncConfig();
        config.getCursorColumns().put("orders", "updated_at");
        stoppedProjectWith(stopped, config);
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveCursorColumn(1L, "ORDERS", "created_at");

        // cursorColumnFor matches case-insensitively; two keys would make the lookup ambiguous.
        assertThat(config.getCursorColumns()).hasSize(1);
        assertThat(config.cursorColumnFor("orders")).isEqualTo("created_at");
        verify(stateWriter).clearCursor(1L, "ORDERS");
    }
}
