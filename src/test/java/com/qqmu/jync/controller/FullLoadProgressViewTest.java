package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

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
