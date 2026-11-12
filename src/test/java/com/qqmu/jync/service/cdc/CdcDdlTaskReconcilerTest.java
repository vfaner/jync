package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.service.sync.SyncStateWriter;

/**
 * A rebuilt target table closes the skipped-DDL tasks that describe work the rebuild already
 * did — and only those.
 */
@ExtendWith(MockitoExtension.class)
class CdcDdlTaskReconcilerTest {

    private static final long PROJECT_ID = 3L;

    @Mock private ChangeLogRepository changeLogRepository;

    private CdcDdlTaskReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new CdcDdlTaskReconciler(changeLogRepository);
    }

    @Test
    void aRebuiltTargetTableClosesItsOpenDdlTasks() {
        // The repair cycle recreated the table from the source definition and reloaded it:
        // both steps the task asks for are done, so the task is not work anymore.
        ChangeLog rebuilt = skippedDdl("ALTER TABLE t ADD COLUMN c INT");
        ChangeLog otherTable = skippedDdl("ALTER TABLE other ADD COLUMN c INT");
        when(changeLogRepository.findOpenDdlTasks(PROJECT_ID))
                .thenReturn(List.of(rebuilt, otherTable));

        int closed = reconciler.resolveAfterTargetRebuild(PROJECT_ID, List.of("T"));

        assertThat(closed).isEqualTo(1);
        assertThat(rebuilt.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PROCESSED);
        assertThat(rebuilt.getDdlExecuted()).isTrue();
        assertThat(rebuilt.getReplayDone()).isTrue();
        assertThat(rebuilt.getErrorDetail()).isNull();
        assertThat(rebuilt.getHandledBy()).isEqualTo(CdcDdlTaskReconciler.REBUILD_HANDLED_BY);
        assertThat(rebuilt.getHandledAt()).isNotNull();
        assertThat(otherTable.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        verify(changeLogRepository).save(rebuilt);
        verify(changeLogRepository, never()).save(otherTable);
    }

    @Test
    void aRebuiltTableDoesNotCloseAStatementThatNamesNoTable() {
        // Repairing one table says nothing about a statement it cannot be attached to:
        // leave it to the administrator rather than closing work nothing performed.
        ChangeLog unnamable = skippedDdl("SET foreign_key_checks = 0");
        when(changeLogRepository.findOpenDdlTasks(PROJECT_ID)).thenReturn(List.of(unnamable));

        assertThat(reconciler.resolveAfterTargetRebuild(PROJECT_ID, List.of("t"))).isZero();
        assertThat(unnamable.getResolutionStatus()).isEqualTo(ChangeLog.RESOLUTION_PENDING);
        verify(changeLogRepository, never()).save(any());
    }

    @Test
    void closingWithoutAnyRebuiltTableDoesNotEvenReadTheTaskList() {
        assertThat(reconciler.resolveAfterTargetRebuild(PROJECT_ID, List.of())).isZero();
        verify(changeLogRepository, never()).findOpenDdlTasks(PROJECT_ID);
    }

    private static ChangeLog skippedDdl(String statement) {
        ChangeLog log = ChangeLog.of(PROJECT_ID, ObjectType.PROJECT, "source_db",
                ChangeType.ERROR, SyncStateWriter.SKIPPED_DDL_PREFIX + statement);
        log.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        log.setDdlExecuted(false);
        log.setTargetDdlSql(statement);
        return log;
    }
}
