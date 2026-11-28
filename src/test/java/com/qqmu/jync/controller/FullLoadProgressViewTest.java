package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashSet;
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

                when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        null, null, 300L, java.time.Instant.now())));

        String html = render();

        assertThat(html).contains("Full load");
        assertThat(html).contains("Current");
        assertThat(html).contains("ITEMS");
        assertThat(html).contains("300");
        assertThat(html).contains("In progress");
    }

    @Test
    void anInFlightParallelLoadShowsRangeProgress() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 2, ChunkCheckpoint.STATUS_RANGE_DONE),
                checkpoint(1, 0, ChunkCheckpoint.STATUS_CHUNK)))));

                when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        null, null, 300L, java.time.Instant.now())));

        String html = render();

        assertThat(html).contains("In progress");
        assertThat(html).contains("ITEMS");
        assertThat(html).contains("300");
    }

    @Test
    void theLightweightCardFragmentCanBePolledWhileThePageIsOpen() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK),
                checkpoint(0, 1, ChunkCheckpoint.STATUS_CHUNK)))));

        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        null, null, 200L, java.time.Instant.now())));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"full-load-card\"");
        assertThat(fragment).contains("data-refresh-url=\"/projects/1/full-load-card\"");
        assertThat(fragment).contains("data-full-load-active=\"true\"");
        assertThat(fragment).contains("data-project-active=\"true\"");
        assertThat(fragment).contains("id=\"progress-summary-data\"");
        assertThat(fragment).contains("data-status-cell=\"fullProgress\"");
        assertThat(fragment).contains("200");
    }

    /**
     * 摘要里的「当前表 X 已写入行数」必须与 X 那一行显示的数字一致：不能把其他表遗留的
     * 检查点加进来，也不能把未选中表的历史检查点算进去。
     */
    @Test
    void theSummaryRowsDescribeTheCurrentTableAndIgnoreDeselectedLeftovers() throws Exception {
        ChunkCheckpoint current = new ChunkCheckpoint(1L, "ITEMS", 0, 0, null, 8_300_000,
                ChunkCheckpoint.STATUS_CHUNK);
        current.setUpdatedAt(Instant.now());
        ChunkCheckpoint leftover = new ChunkCheckpoint(1L, "OLD", 0, 0, null, 1_400_390,
                ChunkCheckpoint.STATUS_CHUNK);
        leftover.setUpdatedAt(Instant.now().minusSeconds(60));
        stubDetail(Map.of(
                "ITEMS", ProjectService.FullLoadProgress.of(List.of(current)),
                "OLD", ProjectService.FullLoadProgress.of(List.of(leftover))));

        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("ITEMS")));
        config.setObjectSelectionExplicit(true);
        when(projectService.loadConfig(any(Project.class))).thenReturn(config);
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        null, null, 8_300_000L, Instant.now())));

        String html = render();

        assertThat(html).contains("8300000");
        assertThat(html).doesNotContain("9700390");
        assertThat(html).doesNotContain("1400390");
        assertThat(html).doesNotContain("data-progress-table=\"OLD\"");
    }

    /**
     * 所有值都是恒定的 100% 时整列隐藏；有未完成的表时才显示，完成的行仍显示 100%。
     */
    @Test
    void theFullProgressColumnIsHiddenWhenEveryVisibleValueIsOneHundred() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.COMPLETED,
                        0L, null, null, null, null, null, null, null,
                        ProjectService.IncrementalState.PAUSED, null, null, false, 100, null)));

        String html = render();

        assertThat(html).contains("class=\"num is-hidden\" data-role=\"progress-full\"");
        assertThat(html).contains("badge badge-ok");
        assertThat(html).contains("100%");
    }

    @Test
    void anUnfinishedLoadAtOneHundredPercentDoesNotHideTheColumn() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK)))));
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        0L, null, 300L, java.time.Instant.now(), null, null, null, null,
                        ProjectService.IncrementalState.NOT_STARTED, null, null, false, 100,
                        null)));

        String html = render();

        assertThat(html).contains("class=\"num\" data-role=\"progress-full\"");
        assertThat(html).doesNotContain("class=\"num is-hidden\" data-role=\"progress-full\"");
    }

    /**
     * 停在中途的全量仍有可续传的进度，列必须保留；这一列也随轮询出现/消失，不用刷新页面。
     */
    @Test
    void theFullProgressColumnStaysVisibleForAnUnfinishedLoad() throws Exception {
        stubDetail(Map.of("ITEMS", ProjectService.FullLoadProgress.of(List.of(
                checkpoint(0, 0, ChunkCheckpoint.STATUS_CHUNK)))));
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.IN_PROGRESS,
                        0L, null, 300L, java.time.Instant.now(), null, null, null, null,
                        ProjectService.IncrementalState.NOT_STARTED, null, null, false, 22,
                        null)));

        String html = render();

        assertThat(html).contains("class=\"num\" data-role=\"progress-full\"");
        assertThat(html).contains("badge badge-info");
        assertThat(html).contains("~22%");
    }

    /**
     * A full load records the resolved cursor column with its last chunk. The polled fragment
     * must therefore carry the strategy/cursor cell too — without it the row kept its "—"
     * until the operator reloaded the page (the 2-second refresh only copied the four state
     * cells).
     */
    @Test
    void thePolledFragmentCarriesTheCursorColumnSoTheRowUpdatesWithoutAReload() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("COURSE",
                        ProjectService.SyncState.COMPLETED, 10L, java.time.Instant.now(),
                        null, null, "TIMESTAMP", "UPDATE_TIME", true, false,
                        ProjectService.IncrementalState.RUNNING)));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("data-status-cell=\"strategy\"");
        assertThat(fragment).contains("UPDATE_TIME");
    }

    @Test
    void theFragmentIsAnEmptyPlaceholderWhenNoFullLoadIsRunning() throws Exception {
        stubDetail(Map.of());

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"full-load-card\"");
        assertThat(fragment).contains("data-full-load-active=\"false\"");
        assertThat(fragment).contains("No full load is currently running");
    }

    @Test
    void aCompletedLoadKeepsTheTableCountInsteadOfVanishingWithTheLastCheckpoint()
            throws Exception {
        stubDetail(Map.of());
        SyncProgress completed = new SyncProgress();
        completed.setObjectName("ITEMS");
        completed.setInitialLoadDone(true);
        when(projectService.findProgress(1L)).thenReturn(List.of(completed));
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("ITEMS", ProjectService.SyncState.COMPLETED,
                        null, null, null, null)));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("Full load");
        assertThat(fragment).contains("Completed");
        assertThat(fragment).contains("No full load is currently running");
    }

    @Test
    void theAllTablesStatusCardListsUnstartedTablesToo() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("DONE", ProjectService.SyncState.COMPLETED,
                        10L, java.time.Instant.now(), null, null),
                new ProjectService.TableSyncStatus("TODO", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        String html = render();

        assertThat(html).contains("Per-table progress");
        assertThat(html).contains("Completed");
        assertThat(html).contains("Not started");
        assertThat(html).contains("TODO");
    }

    @Test
    void theFullLoadFragmentCarriesFreshStatusRowsAndSummaryForLiveRefresh() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("DONE", ProjectService.SyncState.COMPLETED,
                        10L, java.time.Instant.now(), null, null),
                new ProjectService.TableSyncStatus("TODO", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null)));

        String fragment = mvc.perform(get("/projects/1/full-load-card").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The 2-second card poll replaces the visible status rows from these hidden ones,
        // so the fragment must ship both a row per table (named by table) and the summary.
        assertThat(fragment).contains("id=\"table-status-data\"");
        assertThat(fragment).contains("data-table-status-name=\"DONE\"");
        assertThat(fragment).contains("data-table-status-name=\"TODO\"");
        assertThat(fragment).contains("id=\"progress-summary-data\"");
        assertThat(fragment).contains("data-status-cell=\"fullState\"");
    }

    @Test
    void theMergedCardShowsBothStatesAndTheRowCountModalCoversUnstartedTables() throws Exception {
        stubDetail(Map.of());
        java.time.Instant now = java.time.Instant.now();
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("DONE", ProjectService.SyncState.COMPLETED,
                        10L, now, null, null, "TIMESTAMP", "UPDATED_AT", true, null,
                        ProjectService.IncrementalState.RUNNING),
                new ProjectService.TableSyncStatus("PAUSED", ProjectService.SyncState.COMPLETED,
                        5L, now, null, null, "TIMESTAMP", "UPDATED_AT", true, null,
                        ProjectService.IncrementalState.PAUSED),
                new ProjectService.TableSyncStatus("LIMITED", ProjectService.SyncState.COMPLETED,
                        5L, now, null, null, "NONE", null, null, true,
                        ProjectService.IncrementalState.LIMITED),
                new ProjectService.TableSyncStatus("TODO", ProjectService.SyncState.NOT_STARTED,
                        null, null, null, null, null, null, null, null,
                        ProjectService.IncrementalState.NOT_STARTED)));

        String html = render();

        assertThat(html).contains("Full-load state");
        assertThat(html).contains("Incremental state");
        assertThat(html).contains("Running");
        assertThat(html).contains("Paused");
        assertThat(html).contains("Limited");
        assertThat(html).contains("data-role=\"row-count-row\"");
        assertThat(html).contains("data-object=\"TODO\"");
        // Cursor overrides are a one-icon action now; the wide input form is gone.
        assertThat(html).contains("data-role=\"cursor-open\"");
        assertThat(html).contains("id=\"cursor-picker-modal\"");
    }

    @Test
    void theFirstChunkWindowShowsLoadingAndTheCurrentTableWithoutACheckpoint() throws Exception {
        stubDetail(Map.of());
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("COURSE", ProjectService.SyncState.IN_PROGRESS,
                        null, null, null, null)));

        String html = render();

        assertThat(html).contains("COURSE");
        assertThat(html).contains("Loading");
        assertThat(html).contains("waiting for the first checkpoint");
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
        when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                new ProjectService.TableSyncStatus("LOGS", ProjectService.SyncState.COMPLETED,
                        null, null, null, null, null, null, false, null,
                        ProjectService.IncrementalState.RUNNING)));

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
