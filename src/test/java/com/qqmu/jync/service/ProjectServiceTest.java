package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The guards ProjectService keeps between the UI and Quartz/JPA.
 *
 * <p>Mockito rather than {@code @DataJpaTest}: what matters here is what the service refuses
 * to persist and what it copies back onto the entity before saving, and the scheduling and
 * sync collaborators would all need stubbing regardless of how the repository is provided.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

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

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService);
    }

    private Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("proj");
        return p;
    }

    // --- full-load progress (A5 UI panel) -------------------------------------------------

    @Test
    void fullLoadProgressGroupsCheckpointsPerTableAndCountsRanges() {
        when(checkpointRepository.findByProjectId(1L)).thenReturn(java.util.List.of(
                new com.qqmu.jync.model.ChunkCheckpoint(1L, "A", 0, 0, "[1]", 2,
                        com.qqmu.jync.model.ChunkCheckpoint.STATUS_CHUNK),
                new com.qqmu.jync.model.ChunkCheckpoint(1L, "A", 0, 1, "[3]", 2,
                        com.qqmu.jync.model.ChunkCheckpoint.STATUS_CHUNK),
                new com.qqmu.jync.model.ChunkCheckpoint(1L, "B", 0, 2, null, 0,
                        com.qqmu.jync.model.ChunkCheckpoint.STATUS_RANGE_DONE),
                new com.qqmu.jync.model.ChunkCheckpoint(1L, "B", 1, 0, "[9]", 2,
                        com.qqmu.jync.model.ChunkCheckpoint.STATUS_CHUNK)));

        java.util.Map<String, ProjectService.FullLoadProgress> progress =
                service.findFullLoadProgress(1L);

        assertThat(progress).containsOnlyKeys("A", "B");
        assertThat(progress.get("A").getChunksDone()).isEqualTo(2);
        assertThat(progress.get("A").isParallel()).isFalse();
        assertThat(progress.get("B").isParallel()).isTrue();
        assertThat(progress.get("B").getRangesDone()).isEqualTo(1);
        assertThat(progress.get("B").getChunksDone()).isEqualTo(1);
    }

    @Test
    void fullLoadProgressIsEmptyWhenNoLoadIsInFlight() {
        when(checkpointRepository.findByProjectId(1L)).thenReturn(java.util.List.of());

        assertThat(service.findFullLoadProgress(1L)).isEmpty();
    }

    // --- cron pre-validation -------------------------------------------------------------

    @Test
    void aValidCronExpressionIsAccepted() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SyncConfig config = new SyncConfig();
        config.setCronExpression("0 0/30 * * * ?");

        assertThatCode(() -> service.saveConfig(1L, config)).doesNotThrowAnyException();
        verify(scheduler).reschedule(any());
    }

    @Test
    void anInvalidCronExpressionIsRejectedBeforeAnythingIsPersisted() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));

        SyncConfig config = new SyncConfig();
        config.setCronExpression("abc");

        // Left to Quartz this fails inside the save transaction with a bare RuntimeException:
        // a 500 for the user and a rollback that silently discards the rest of the form.
        assertThatThrownBy(() -> service.saveConfig(1L, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.cron.invalid");
        verify(projectRepository, never()).save(any());
        verify(scheduler, never()).reschedule(any());
    }

    @Test
    void aBlankCronMeansIntervalModeAndIsNotValidated() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SyncConfig config = new SyncConfig();
        config.setCronExpression("   ");

        assertThatCode(() -> service.saveConfig(1L, config)).doesNotThrowAnyException();
    }

    @Test
    void saveConfigRejectsAMappingThatPointsTwoTablesAtOneTarget() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));

        SyncConfig config = new SyncConfig();
        config.getTableNameMapping().put("A", "X");
        config.getTableNameMapping().put("B", "X");

        assertThatThrownBy(() -> service.saveConfig(1L, config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("error.project.tablemapping.duplicate:");
        verify(projectRepository, never()).save(any());
        verify(scheduler, never()).reschedule(any());
    }

    // --- lost-update guard on save ---------------------------------------------------------

    @Test
    void anUpdateRestoresTheFieldsTheEditFormDoesNotCarry() {
        Instant createdAt = Instant.parse("2025-01-01T00:00:00Z");
        Project existing = project(1L);
        existing.setEnabled(true);
        existing.setSyncConfig("{\"tables\":[]}");
        existing.setCreatedAt(createdAt);
        when(projectRepository.findByName("proj")).thenReturn(Optional.empty());
        when(projectRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // What the form binds: only the descriptive fields, with entity defaults elsewhere.
        service.save(project(1L));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        // A stale browser tab must not be able to stop a running project or wipe its config.
        assertThat(captor.getValue().getEnabled()).isTrue();
        assertThat(captor.getValue().getSyncConfig()).isEqualTo("{\"tables\":[]}");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void updatingARowThatVanishedIsRejectedWithAnI18nKey() {
        when(projectRepository.findByName("proj")).thenReturn(Optional.empty());
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(project(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.not.found");
        verify(projectRepository, never()).save(any());
    }

    @Test
    void creatingANewProjectDoesNotGoThroughThePreservationPath() {
        when(projectRepository.findByName("proj")).thenReturn(Optional.empty());
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(project(null));

        verify(projectRepository, never()).findById(any());
    }

    // --- delete guard -----------------------------------------------------------------------

    @Test
    void deleteRefusesWhileASyncCycleHoldsTheLock() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(lockService.tryAcquire(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.sync.in.progress");
        // The refusal must precede every state purge: a half-deleted project coexisting
        // with a cycle that still writes to it is worse than either alone.
        verify(taskStore, never()).deleteForProject(any());
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void deletePurgesEveryStateStoreWhileHoldingTheSyncLock() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lockService.tryAcquire(1L)).thenReturn(lock);

        service.delete(1L);

        verify(scheduler).unschedule(1L);
        verify(taskStore).deleteForProject(1L);
        verify(syncEngine).evictProjectCaches(1L);
        verify(lockService).evictProject(1L);
        verify(progressRepository).deleteByProjectId(1L);
        verify(snapshotService).deleteAllForProject(1L);
        verify(changeLogRepository).deleteByProjectId(1L);
        verify(projectRepository).deleteById(1L);
        verify(lock).close();
        verify(auditService).record(eq(AuditAction.PROJECT_DELETE), contains("proj"));
    }

    // --- force unlock ------------------------------------------------------------------------

    @Test
    void forceUnlockClearsTheLeaseAndAuditsActorAndPreviousHolder() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        SyncTask task = new SyncTask();
        task.setLockOwner("node-A");
        task.setLockExpiresAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(taskStore.find(1L)).thenReturn(Optional.of(task));
        when(lockService.forceUnlock(1L)).thenReturn(true);
        // Three-arg constructor: the two-arg one stays unauthenticated, and the audit
        // deliberately records "unknown" for an unauthenticated context.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, java.util.List.of()));

        service.forceUnlock(1L);

        verify(lockService).forceUnlock(1L);
        // Both trails get the event: the change log can be cleared per project, the
        // append-only audit trail cannot.
        verify(auditService).record(eq(AuditAction.PROJECT_FORCE_UNLOCK),
                contains("node-A"));
        // The audit entry is the only record that a live lease was dropped by hand, so it
        // must name the actor, the previous holder and the lease that was cut short.
        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogRepository).save(captor.capture());
        ChangeLog entry = captor.getValue();
        assertThat(entry.getProjectId()).isEqualTo(1L);
        assertThat(entry.getObjectType()).isEqualTo(ObjectType.PROJECT);
        assertThat(entry.getChangeType()).isEqualTo(ChangeType.INFO);
        assertThat(entry.getSuccess()).isTrue();
        assertThat(entry.getDetails())
                .contains("admin")
                .contains("node-A")
                .contains("2026-01-01T00:00:00Z");
    }

    @Test
    void forceUnlockIsRefusedWhenNoLeaseIsHeld() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(taskStore.find(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceUnlock(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.lock.not.held");
        verify(lockService, never()).forceUnlock(1L);
        verify(changeLogRepository, never()).save(any());
    }

    // --- transaction follow-ups -------------------------------------------------------------

    @AfterEach
    void clearSynchronizations() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void aRolledBackDeleteRestoresTheScheduleAndTheRunningStatus() {
        Project enabled = project(1L);
        enabled.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(enabled));
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lockService.tryAcquire(1L)).thenReturn(lock);

        TransactionSynchronizationManager.initSynchronization();
        service.delete(1L);

        // Simulate the outer transaction rolling back after delete() returned.
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        // unschedule() committed STOPPED in its own transaction; restore both halves.
        verify(taskStore).markRunning(1L);
        verify(scheduler).schedule(enabled);
    }

    @Test
    void aCommittedDeleteDoesNotRestoreTheSchedule() {
        Project enabled = project(1L);
        enabled.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(enabled));
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lockService.tryAcquire(1L)).thenReturn(lock);

        TransactionSynchronizationManager.initSynchronization();
        service.delete(1L);

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        verify(taskStore, never()).markRunning(any());
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void aFailingPostCommitRescheduleIsContained() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(contextFactory.serializeConfig(any())).thenReturn("{}");
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Spring's afterCommit dispatcher would otherwise swallow this with a framework
        // line only; the guarded callback must log it with the project id and not throw.
        org.mockito.Mockito.doThrow(new IllegalStateException("scheduler down"))
                .when(scheduler).reschedule(any());

        TransactionSynchronizationManager.initSynchronization();
        service.saveConfig(1L, new SyncConfig());

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        }
    }

    // --- cursor candidates -----------------------------------------------------------------

    @Test
    void cursorCandidatesWithoutASourceConnectionAreRejectedWithAnI18nKey() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));

        // Endpoints are only mandatory at start(), so the null id would otherwise reach
        // findById(null) and leak its internal English error to the user.
        assertThatThrownBy(() -> service.listCursorCandidates(1L, "orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.source.required");
        verify(databaseConfigRepository, never()).findById(any());
    }
}
