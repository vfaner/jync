package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
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
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ConnectionTestService connectionTestService;
    @Mock private CryptoUtil cryptoUtil;
    @Mock private AdminAuditService auditService;

    private DatabaseConfigService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, projectRepository,
                dataSourceManager, connectionTestService, cryptoUtil, auditService);
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

        assertThatThrownBy(() -> service.save(incoming(5L), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.edit.running:orders-sync");
        verify(repository, never()).save(any());
        verify(dataSourceManager, never()).evict(any());
    }

    @Test
    void editingAConnectionUsedOnlyByStoppedProjectsIsAllowed() {
        DatabaseConfig existing = incoming(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(projectRepository.findBySourceDbIdOrTargetDbId(5L, 5L))
                .thenReturn(List.of(project("orders-sync", false)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.save(incoming(5L), null)).doesNotThrowAnyException();
        verify(repository).save(any());
    }
}
