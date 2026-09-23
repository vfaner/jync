package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * A preset whose driver is not bundled (GBase / Oscar) must not be savable without a jar path.
 * Letting it through means the failure appears much later — at a scheduled sync, in English,
 * with no form to fix it from.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseConfigServiceValidationTest {

    @Mock private DatabaseConfigRepository repository;
    @Mock private ProjectRepository projectRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private ConnectionTestService connectionTestService;
    @Mock private CryptoUtil cryptoUtil;

    private DatabaseConfigService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, projectRepository,
                dataSourceManager, connectionTestService, cryptoUtil);
    }

    private DatabaseConfig preset(DatabaseType type, String jarPath) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("conn-" + type.name());
        c.setType(type);
        c.setRole(ConnectionRole.SOURCE);
        c.setHost("localhost");
        c.setCustomJarPath(jarPath);
        return c;
    }

    private Project project(String name) {
        Project p = new Project();
        p.setName(name);
        return p;
    }

    private DatabaseConfig stored(long id, ConnectionRole role) {
        DatabaseConfig existing = new DatabaseConfig();
        existing.setId(id);
        existing.setName("conn-x");
        existing.setType(DatabaseType.MYSQL);
        existing.setRole(role);
        existing.setHost("localhost");
        return existing;
    }

    @Test
    void gbaseWithoutJarIsRejected() {
        assertThatThrownBy(() -> service.save(preset(DatabaseType.GBASE, "  "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.driver.jar.required");
        verify(repository, never()).save(any());
    }

    @Test
    void oscarWithoutJarIsRejected() {
        assertThatThrownBy(() -> service.save(preset(DatabaseType.OSCAR, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.driver.jar.required");
    }

    @Test
    void gbaseWithJarIsAccepted() {
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt(any())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(preset(DatabaseType.GBASE, "/opt/drivers/gbase.jar"), null);

        verify(repository).save(any());
    }

    @Test
    void bundledDamengNeedsNoJar() {
        when(repository.findByName(any())).thenReturn(Optional.empty());
        when(cryptoUtil.encrypt(any())).thenReturn("enc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(preset(DatabaseType.DM, null), null);

        verify(repository).save(any());
    }

    @Test
    void missingRoleIsRejected() {
        DatabaseConfig c = preset(DatabaseType.MYSQL, null);
        c.setRole(null);

        assertThatThrownBy(() -> service.save(c, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.role.required");
        verify(repository, never()).save(any());
    }

    @Test
    void changingSourceToTargetIsBlockedWhenProjectsUseItAsSource() {
        DatabaseConfig incoming = stored(1L, ConnectionRole.TARGET);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(1L, ConnectionRole.SOURCE)));
        when(repository.findByName("conn-x")).thenReturn(Optional.of(stored(1L, ConnectionRole.SOURCE)));
        when(projectRepository.findBySourceDbId(1L)).thenReturn(List.of(project("proj-a")));

        assertThatThrownBy(() -> service.save(incoming, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.role.source.in.use:proj-a");
        verify(repository, never()).save(any());
    }

    @Test
    void changingTargetToSourceIsBlockedWhenProjectsUseItAsTarget() {
        DatabaseConfig incoming = stored(1L, ConnectionRole.TARGET);
        incoming.setRole(ConnectionRole.SOURCE);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(1L, ConnectionRole.TARGET)));
        when(repository.findByName("conn-x")).thenReturn(Optional.of(stored(1L, ConnectionRole.TARGET)));
        when(projectRepository.findByTargetDbId(1L)).thenReturn(List.of(project("proj-b")));

        assertThatThrownBy(() -> service.save(incoming, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.connection.role.target.in.use:proj-b");
        verify(repository, never()).save(any());
    }

    @Test
    void roleChangeIsAllowedWhenNoProjectBlocksIt() {
        DatabaseConfig incoming = stored(1L, ConnectionRole.TARGET);
        when(repository.findById(1L)).thenReturn(Optional.of(stored(1L, ConnectionRole.SOURCE)));
        when(repository.findByName("conn-x")).thenReturn(Optional.of(stored(1L, ConnectionRole.SOURCE)));
        when(projectRepository.findBySourceDbId(1L)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.save(incoming, null);

        verify(repository).save(any());
    }

    @Test
    void backfillInfersRolesFromProjectReferencesAndDefaultsUnreferencedToSource() {
        DatabaseConfig usedAsSource = stored(10L, null);
        usedAsSource.setName("s");
        DatabaseConfig usedAsTarget = stored(11L, null);
        usedAsTarget.setName("t");
        DatabaseConfig bothWays = stored(12L, null);
        bothWays.setName("b");
        DatabaseConfig unreferenced = stored(13L, null);
        unreferenced.setName("u");
        DatabaseConfig alreadyClassified = stored(14L, ConnectionRole.TARGET);
        alreadyClassified.setName("ok");
        when(repository.findAll()).thenReturn(List.of(
                usedAsSource, usedAsTarget, bothWays, unreferenced, alreadyClassified));
        when(projectRepository.findBySourceDbId(10L)).thenReturn(List.of(project("p1")));
        when(projectRepository.findBySourceDbId(11L)).thenReturn(List.of());
        when(projectRepository.findByTargetDbId(11L)).thenReturn(List.of(project("p2")));
        when(projectRepository.findBySourceDbId(12L)).thenReturn(List.of(project("p3")));
        when(projectRepository.findBySourceDbId(13L)).thenReturn(List.of());
        when(projectRepository.findByTargetDbId(13L)).thenReturn(List.of());

        service.backfillRoles();

        ArgumentCaptor<List<DatabaseConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<DatabaseConfig> saved = captor.getValue();
        assertThat(saved).extracting(DatabaseConfig::getId).containsExactly(10L, 11L, 12L, 13L);
        assertThat(saved).extracting(DatabaseConfig::getRole).containsExactly(
                ConnectionRole.SOURCE, ConnectionRole.TARGET,
                ConnectionRole.SOURCE, ConnectionRole.SOURCE);
    }

    @Test
    void backfillDoesNothingWhenEveryConnectionHasARole() {
        when(repository.findAll()).thenReturn(List.of(stored(1L, ConnectionRole.SOURCE)));

        service.backfillRoles();

        verify(repository, never()).saveAll(any());
    }

    @Test
    void deletingAMissingConnectionIsRejectedWithAnI18nKey() {
        when(repository.existsById(42L)).thenReturn(false);

        // deleteById would throw EmptyResultDataAccessException, which a double-clicked or
        // two-tab delete would surface as a 500 instead of a plain "already gone" message.
        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.missing");
        verify(repository, never()).deleteById(any());
    }
}
