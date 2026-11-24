package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Editing a connection that a RUNNING project syncs through is not a cosmetic change:
 * saving evicts the cached pool, so the next acquisition in an in-flight cycle switches
 * host/credentials mid-cycle, and a CDC consumer keeps its old config snapshot until
 * restarted — the UI would claim a connection the consumer is not actually using.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServiceRunningEditTest {

    @Mock private DatabaseConfigRepository repository;
    @Mock private ProjectRepository projectRepository;
    @Mock private com.qqmu.jync.service.task.SyncTaskStore taskStore;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ConnectionTestService connectionTestService;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;
    @Mock private com.qqmu.jync.repository.DbMetricSampleRepository dbMetricSampleRepository;

    private DatabaseConfigService service;

    @BeforeEach
    void setUp() {
        lenient().when(taskStore.find(any())).thenReturn(Optional.empty());
        service = new DatabaseConfigService(repository, projectRepository,
                taskStore, dataSourceManager, connectionTestService, cryptoUtil, auditService, dbMetricSampleRepository);
    }

    private DatabaseConfig incoming(long id) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName("conn-x");
        c.setType(DatabaseType.MYSQL);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("db.example.com");
        return c;
    }

    private Project project(String name, boolean enabled) {
        Project p = new Project();
        p.setName(name);
        p.setEnabled(enabled);
        return p;
    }

    @Test
    @DisplayName("an edit rejected while a project runs leaves the row and the pool untouched")
    void editingAConnectionUsedByAnEnabledProjectIsRefused() {
        DatabaseConfig existing = incoming(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(project("orders-sync", true)));

        assertThatThrownBy(() -> service.save(incoming(5L), null, null, AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.edit.running:orders-sync");
        verify(repository, never()).save(any());
        verify(dataSourceManager, never()).evict(any());
    }

    @Test
    void changingOnlyExtraParamsStillRefusesARunningDependentProject() {
        DatabaseConfig existing = incoming(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(project("orders-sync", true)));

        assertThatThrownBy(() -> service.updateExtraParams(5L,
                java.util.Map.of("connectTimeout", "5000"), java.util.List.of(),
                AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                // No ":project" suffix: this key is rendered as an assistant card result.
                .hasMessage("error.connection.edit.running");
        verify(repository, never()).save(any());
    }

    @Test
    void anOperatorCannotChangeAParameterUnderAnotherAccountsTask() {
        DatabaseConfig existing = incoming(5L);
        existing.setOwnerUsername("ops1");
        Project foreign = project("alice-sync", false);
        foreign.setOwnerUsername("alice");
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.updateExtraParams(5L,
                java.util.Map.of("connectTimeout", "5000"), java.util.List.of(),
                AccessGuard.of("ops1", com.qqmu.jync.model.UserRole.OPS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.sharedByOthers");
        verify(repository, never()).save(any());
    }

    @Test
    void aStoppedProjectStillFinishingItsRoundAlsoBlocksTheEdit() {
        DatabaseConfig existing = incoming(5L);
        existing.setOwnerUsername("ops1");
        Project stopped = project("orders-sync", false);
        stopped.setId(9L);
        stopped.setOwnerUsername("ops1");
        SyncTask task = new SyncTask();
        task.setProjectId(9L);
        task.setLockOwner("node-a");
        task.setLockExpiresAt(java.time.Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(stopped));
        when(taskStore.find(9L)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateExtraParams(5L,
                java.util.Map.of("connectTimeout", "5000"), java.util.List.of(),
                AccessGuard.of("ops1", com.qqmu.jync.model.UserRole.OPS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.edit.running");
        verify(repository, never()).save(any());
    }

    @Test
    void extraParamsAreMergedAgainstTheFreshRowAndThePoolIsEvicted() {
        DatabaseConfig existing = incoming(5L);
        existing.setExtraParams("useSSL=false");
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(project("orders-sync", false)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateExtraParams(5L, java.util.Map.of("connectTimeout", "5000"),
                java.util.List.of(), AccessGuard.system());

        assertThat(existing.getExtraParams()).isEqualTo("useSSL=false&connectTimeout=5000");
        verify(dataSourceManager).evict(5L);
    }

    @Test
    void editingAConnectionUsedOnlyByStoppedProjectsIsAllowed() {
        DatabaseConfig existing = incoming(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(project("orders-sync", false)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.save(incoming(5L), null, null, AccessGuard.system())).doesNotThrowAnyException();
        verify(repository).save(any());
    }
}
