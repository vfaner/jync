package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChunkCheckpoint;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * The in-flight full-load panel on the project detail page (task book A5).
 *
 * <p>Checkpoint rows exist only while a table's initial load is unfinished, so the badge is
 * exactly the "this load is still running / will resume" signal: it must name the split mode
 * (sequential chunks vs parallel ranges), the committed chunk count, and carry the resume
 * promise as its tooltip — all resolved through i18n, never as raw keys.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class FullLoadProgressViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private void stubDetail(Map<String, ProjectService.FullLoadProgress> fullLoad) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(true);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());

        SyncProgress progress = new SyncProgress();
        progress.setObjectName("ITEMS");
        when(projectService.findProgress(1L)).thenReturn(List.of(progress));
        when(projectService.findFullLoadProgress(1L)).thenReturn(fullLoad);
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
    }

    private String render() throws Exception {
        return mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anInFlightSequentialLoadShowsChunkCountAndResumeHint() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 2, ChunkCheckpoint.STATUS_CHUNK)))));

        String html = render();

        assertThat(html).contains("Chunked full load in progress");
        assertThat(html).contains("3 chunks committed");
        assertThat(html).contains("300 rows written");
        assertThat(html).contains("Full-load progress");
        assertThat(html).contains("Current table");
        assertThat(html).contains("ITEMS");
        assertThat(html).contains("resumes from the last committed chunk");
    }

    @Test
    void anInFlightParallelLoadShowsRangeProgress() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 2, ChunkCheckpoint.STATUS_RANGE_DONE),
                checkpoint(1, 0, ChunkCheckpoint.STATUS_CHUNK)))));

        String html = render();

        assertThat(html).contains("Parallel full load: 1 of 2 key ranges done");
        assertThat(html).contains("3 chunks committed");
    }

    @Test
    void theLightweightCardFragmentCanBePolledWhileThePageIsOpen() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK)))));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"full-load-card\"");
        assertThat(fragment).contains("data-refresh-url=\"/projects/1/full-load-card\"");
        assertThat(fragment).contains("Current table");
        assertThat(fragment).contains("200 rows written");
    }

    @Test
    void theFragmentIsAnEmptyPlaceholderWhenNoFullLoadIsRunning() throws Exception {
        stubDetail(Map.of());

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"full-load-card\"");
        assertThat(fragment).doesNotContain("Full-load progress");
    }

    @Test
    void thePerTableBadgeFragmentCanBePolledWithoutReplacingTheWholeRow() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK)))));

        String fragment = mvc.perform(get("/projects/1/progress-badges").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("data-full-load-badge-table=\"ITEMS\"");
        assertThat(fragment).contains("2 chunks committed");
        assertThat(fragment).contains("200 rows written");
    }

    @Test
    void aCompletedLoadKeepsTheTableCountInsteadOfVanishingWithTheLastCheckpoint()
            throws Exception {
        stubDetail(Map.of());
        SyncProgress completed = new SyncProgress();
        completed.setObjectName("ITEMS");
        completed.setInitialLoadDone(true);
        when(projectService.findProgress(1L)).thenReturn(List.of(completed));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("Full-load progress");
        assertThat(fragment).contains("Full load complete: 1/1 tables");
        assertThat(fragment).contains("No full load is currently running");
    }

    @Test
    void theAllTablesStatusCardListsUnstartedTablesToo() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("DONE", ProjectService.SyncState.COMPLETED,
                        10L, java.time.Instant.now(), null, null),
                new ProjectService.TableSyncStatus("TODO", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        String html = render();

        assertThat(html).contains("Table status");
        assertThat(html).contains("Full load complete");
        assertThat(html).contains("Not started");
        assertThat(html).contains("TODO");
    }

    @Test
    void aTableWithoutCheckpointsShowsNoFullLoadBadge() throws Exception {
        stubDetail(Map.of());

        String html = render();

        assertThat(html).doesNotContain("chunks committed");
        assertThat(html).doesNotContain("Chunked full load");
    }

    @Test
    void aPkLessTableCarriesTheNoResumeBadge() throws Exception {
        stubDetail(Map.of());
        SyncProgress keyless = new SyncProgress();
        keyless.setObjectName("LOGS");
        keyless.setResumableLoad(false);
        when(projectService.findProgress(1L)).thenReturn(List.of(keyless));

        String html = render();

        // Task book A1: this particular streamed load of a PK-less table could not resume; the
        // page must say so, and the tooltip explains when a keyless table can resume and that a
        // primary key is always safer.
        assertThat(html).contains("No checkpointed resume");
        assertThat(html).contains("Keyless tables can resume in pages when the target is empty");
        assertThat(html).contains("adding a primary key is always the safer option");
    }

    @Test
    void aResumableOrLegacyRowShowsNoResumeBadge() throws Exception {
        stubDetail(Map.of());

        // The stubbed ITEMS row has resumableLoad == null (a pre-feature progress row):
        // absence of knowledge must not render as a warning.
        assertThat(render()).doesNotContain("No checkpointed resume");
    }

    private static ChunkCheckpoint checkpoint(int range, int chunk, String status) {
        return new ChunkCheckpoint(7L, "ITEMS", range, chunk, null, 100, status);
    }
}
