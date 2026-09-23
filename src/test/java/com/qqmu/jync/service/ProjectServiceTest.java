package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import com.qqmu.jync.service.task.SyncContextFactory;
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
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, dataSourceManager, readerFactory,
                snapshotService, contextFactory, scheduler, taskRunner, taskStore, syncEngine);
    }

    private Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("proj");
        return p;
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
