package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncTaskRunner;

@ExtendWith(MockitoExtension.class)
class TableMaintenanceServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncTaskRunner taskRunner;

    private TableMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new TableMaintenanceService(projectRepository, changeLogRepository,
                contextFactory, taskRunner);
    }

    @Test
    void reloadRefusesARunningProject() {
        Project project = project();
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> service.reload(1L, "orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.table.reload.stopFirst");
        verify(taskRunner, never()).runTableReload(anyLong(), anyString());
    }

    @Test
    void reloadRefusesAFanoutProject() {
        Project project = project();
        SyncConfig config = config("orders");
        config.setFanoutTargetDbIds(List.of(2L));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.parseConfig(project)).thenReturn(config);

        assertThatThrownBy(() -> service.reload(1L, "orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.table.reload.fanout");
        verify(taskRunner, never()).runTableReload(anyLong(), anyString());
    }

    @Test
    void reloadRefusesATableOutsideAnExplicitSelection() {
        Project project = project();
        SyncConfig config = config("customers");
        config.setObjectSelectionExplicit(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.parseConfig(project)).thenReturn(config);

        assertThatThrownBy(() -> service.reload(1L, "orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.table.reload.notSelected");
        verify(taskRunner, never()).runTableReload(anyLong(), anyString());
    }

    @Test
    void reloadResolvesTheSavedTableSpellingBeforeDelegating() {
        Project project = project();
        SyncConfig config = config("Orders");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contextFactory.parseConfig(project)).thenReturn(config);
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", new SyncResult());
        when(taskRunner.runTableReload(1L, "Orders")).thenReturn(outcome);

        service.reload(1L, "orders");

        verify(taskRunner).runTableReload(1L, "Orders");
    }

    private static Project project() {
        Project project = new Project();
        project.setId(1L);
        project.setName("orders-sync");
        project.setTargetKind(SyncTargetKind.JDBC);
        project.setEnabled(false);
        return project;
    }

    private static SyncConfig config(String... tables) {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of(tables)));
        config.setSyncData(true);
        return config;
    }
}
