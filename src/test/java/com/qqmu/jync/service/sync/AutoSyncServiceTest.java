package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Auto-sync must only add tables that appeared after the switch was enabled, and must keep
 * the snapshot/selection persistent so a restart does not sweep in old unselected tables.
 */
class AutoSyncServiceTest {

    private ProjectRepository projects;
    private ObjectMapper mapper;
    private SyncStateWriter stateWriter;
    private AutoSyncService service;
    private Project project;
    private SyncConfig config;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        mapper = mock(ObjectMapper.class);
        stateWriter = mock(SyncStateWriter.class);
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        service = new AutoSyncService(projects, mapper, stateWriter);

        project = new Project();
        project.setId(1L);
        project.setName("proj");

        config = new SyncConfig();
        config.setObjectSelectionExplicit(true);
        config.setTables(new LinkedHashSet<>(List.of("KEEP")));
        config.setAutoSyncNewTables(true);
    }

    @Test
    void theFirstCycleSnapshotsExistingTablesWithoutAddingAny() {
        List<String> added = service.reconcile(project, config,
                List.of("KEEP", "OLD_UNSELECTED"));

        assertThat(added).isEmpty();
        assertThat(config.getAutoSyncKnownTables()).containsExactly("KEEP", "OLD_UNSELECTED");
        assertThat(config.getTables()).containsExactly("KEEP");
        verify(projects).save(project);
        verify(stateWriter, never()).recordLog(anyLong(), any(), anyString(), any(),
                anyString(), any(Boolean.class), anyLong());
    }

    @Test
    void tablesAppearingAfterTheSnapshotAreAddedAndLogged() {
        service.reconcile(project, config, List.of("KEEP", "OLD_UNSELECTED"));

        List<String> added = service.reconcile(project, config,
                List.of("KEEP", "OLD_UNSELECTED", "NEW_TABLE"));

        assertThat(added).containsExactly("NEW_TABLE");
        assertThat(config.getTables()).containsExactlyInAnyOrder("KEEP", "NEW_TABLE");
        assertThat(config.getAutoSyncKnownTables()).contains("NEW_TABLE");
        verify(stateWriter).recordLog(eqId(), any(), anyString(), any(ChangeType.class),
                anyString(), any(Boolean.class), anyLong());
    }

    @Test
    void matchingIsCaseInsensitiveAndKnownTablesAreNotAddedTwice() {
        config.setAutoSyncKnownTables(new ArrayList<>(List.of("NEW_TABLE")));
        service.reconcile(project, config, List.of("KEEP", "NEW_TABLE"));

        assertThat(service.reconcile(project, config,
                List.of("KEEP", "new_table", "New_Table"))).isEmpty();
    }

    @Test
    void aSingleCycleNeverAddsMoreThanTheSafetyCap() {
        config.setAutoSyncKnownTables(new ArrayList<>());
        List<String> source = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            source.add("T" + i);
        }

        List<String> added = service.reconcile(project, config, source);

        assertThat(added).hasSize(AutoSyncService.MAX_PER_CYCLE);
        // The pre-existing selection (KEEP) stays; only the additions are capped.
        assertThat(config.getTables()).hasSize(AutoSyncService.MAX_PER_CYCLE + 1);
    }

    @Test
    void legacySyncEverythingProjectsNeedNoAutoSync() {
        SyncConfig all = new SyncConfig();
        all.setAutoSyncNewTables(true);
        all.setObjectSelectionExplicit(false);

        assertThat(service.reconcile(project, all, List.of("A", "B"))).isEmpty();
        verify(projects, never()).save(any());
    }

    @Test
    void theEligibilityPredicateCanRejectKeylessTables() {
        config.setAutoSyncKnownTables(new ArrayList<>(List.of("KEEP")));

        List<String> added = service.reconcile(project, config,
                List.of("KEEP", "NO_PK", "WITH_PK"),
                table -> !table.equals("NO_PK"));

        assertThat(added).containsExactly("WITH_PK");
        assertThat(config.getTables()).doesNotContain("NO_PK");
    }

    @Test
    void rollbackRemovesABatchSoTheNextAttemptSeesItAsNew() {
        service.reconcile(project, config, List.of("KEEP"));
        List<String> added = service.reconcile(project, config, List.of("KEEP", "NEW"));

        service.rollback(project, config, added);

        assertThat(config.getTables()).doesNotContain("NEW");
        assertThat(config.getAutoSyncKnownTables()).doesNotContain("NEW");
    }

    @Test
    void disabledSwitchDoesNothing() {
        config.setAutoSyncNewTables(false);

        assertThat(service.reconcile(project, config, List.of("A"))).isEmpty();
        verify(projects, never()).save(any());
    }

    private static Long eqId() {
        return org.mockito.ArgumentMatchers.eq(1L);
    }
}
