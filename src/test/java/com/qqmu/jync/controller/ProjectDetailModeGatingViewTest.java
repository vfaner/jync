package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * Controls that belong to one mode or one role must be gated on the server, not only by the
 * page's JavaScript.
 *
 * <p>{@code app.js} re-shows every {@code data-role="polling-only"} field whenever the selected
 * strategy is polling — with no notion of who is looking at the page. A read-only account then
 * sees an editable per-table cursor form it can submit but the server will refuse (every POST is
 * admin-only). The same file must not offer a database action on a Redis target that has no
 * database, and must not show two different "rebuild" actions for a cache project.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailModeGatingViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;
    @MockitoBean private com.qqmu.jync.service.fanout.FanoutPositionStore fanoutPositionStore;

    private Project stubProject(SyncTargetKind kind, CaptureStrategy strategy, boolean withProgress) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        project.setSourceDbId(2L);
        project.setTargetKind(kind);
        if (kind == SyncTargetKind.REDIS) {
            project.setTargetRedisId(9L);
        } else {
            project.setTargetDbId(3L);
        }
        when(projectService.findById(1L)).thenReturn(Optional.of(project));

        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        config.getTables().add("orders");
        config.setObjectSelectionExplicit(true);
        when(projectService.loadConfig(project)).thenReturn(config);

        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        if (withProgress) {
            SyncProgress progress = new SyncProgress();
            progress.setObjectType(ObjectType.TABLE);
            progress.setObjectName("orders");
            when(projectService.findProgress(1L)).thenReturn(List.of(progress));
            when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of(
                    new ProjectService.TableSyncStatus("orders",
                            ProjectService.SyncState.COMPLETED, 10L, null, null, null,
                            "TIMESTAMP", "UPDATED_AT", true, null,
                            ProjectService.IncrementalState.RUNNING)));
        } else {
            when(projectService.findProgress(1L)).thenReturn(List.of());
            when(projectService.findTableSyncStatuses(eq(1L), any(), any())).thenReturn(List.of());
        }
        return project;
    }

    private String renderDetail() throws Exception {
        return mvc.perform(get("/projects/1").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aFanoutProjectShowsTheTransportChosenWhenItWasSaved() throws Exception {
        Project project = stubProject(SyncTargetKind.JDBC, CaptureStrategy.PG_LOGICAL_CDC, false);
        SyncConfig fanout = new SyncConfig();
        fanout.setCaptureStrategy(CaptureStrategy.PG_LOGICAL_CDC);
        fanout.getTables().add("orders");
        fanout.setObjectSelectionExplicit(true);
        fanout.setFanoutTargetDbIds(new java.util.ArrayList<>(List.of(4L)));
        when(projectService.loadConfig(project)).thenReturn(fanout);

        String page = renderDetail();

        // The relay is fixed at create/edit time; the detail card only reports it.
        assertThat(page).contains("fanout-transport-badge");
        assertThat(page).contains("Local spool");
        assertThat(page).doesNotContain("name=\"fanoutTransport\"",
                "name=\"fanoutRedisTargetId\"", "value=\"REDIS\"");
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void aReadOnlyAccountNeverGetsTheCursorOverrideFieldInTheJavascriptHook() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        // The cell is hidden on the server and carries no data-role, so app.js's strategy
        // switch cannot reveal it; a viewer never sees an input whose POST would be a 403.
        String cell = enclosingTag(page, "data-role=\"cursor-open\"", "<td");
        assertThat(cell).contains("is-hidden").doesNotContain("data-role=\"polling-only\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminKeepsTheCursorOverrideFieldForAPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        String cell = enclosingTag(page, "data-role=\"cursor-open\"", "<td");
        assertThat(cell).contains("data-role=\"polling-only\"").doesNotContain("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminGetsTheBulkCursorEditorForAStoppedPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, false);

        String page = renderDetail();

        assertThat(page).contains("data-role=\"cursor-priority-open\"")
                .contains("id=\"cursor-priority-modal\"")
                .contains("data-role=\"cursor-priority-input\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aRunningProjectDoesNotOfferTheBulkCursorEditor() throws Exception {
        Project project = stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, false);
        project.setEnabled(true);

        String page = renderDetail();

        // Configuration edits require a stop; hiding the control matches the per-table form.
        assertThat(page).doesNotContain("data-role=\"cursor-priority-open\"")
                .doesNotContain("id=\"cursor-priority-modal\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aCdcProjectDoesNotOfferTheBulkCursorEditor() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, false);

        String page = renderDetail();

        assertThat(page).doesNotContain("data-role=\"cursor-priority-open\"")
                .doesNotContain("id=\"cursor-priority-modal\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aFullCompareProjectDoesNotOfferTheBulkCursorEditor() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.FULL_COMPARE, false);

        String page = renderDetail();

        // Full compare never reads a cursor column, so the setting would be inert.
        assertThat(page).doesNotContain("data-role=\"cursor-priority-open\"")
                .doesNotContain("id=\"cursor-priority-modal\"");
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void aViewerDoesNotGetTheBulkCursorEditor() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, false);

        String page = renderDetail();

        assertThat(page).doesNotContain("data-role=\"cursor-priority-open\"")
                .doesNotContain("id=\"cursor-priority-modal\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theCursorExplanationIsHiddenForACdcProject() throws Exception {
        // The hint says a cursor column reads only what changed — true for polling, false
        // for log CDC, where changes come from the log stream. Leaving it up next to the
        // (unused) cursor column invites editing a value that changes nothing.
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, true);

        String page = renderDetail();

        String hint = enclosingTag(page, "data-role=\"cursor-help\"", "<span");
        assertThat(hint).contains("is-hidden")
                .contains("data-cdc-visibility=\"non-cdc\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theCursorExplanationIsShownForAPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        String hint = enclosingTag(page, "data-role=\"cursor-help\"", "<span");
        assertThat(hint).doesNotContain("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theProgressStrategyColumnIsHiddenForACdcProject() throws Exception {
        // 游标策略只属于轮询/全量比对；CDC 的变更来自日志流，展示「数字主键」会误导。
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, true);

        String page = renderDetail();

        String th = enclosingTag(page, "data-role=\"progress-strategy\"", "<th");
        assertThat(th).contains("is-hidden").contains("data-cdc-visibility=\"non-cdc\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theProgressStrategyColumnIsShownForAPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        String th = enclosingTag(page, "data-role=\"progress-strategy\"", "<th");
        assertThat(th).doesNotContain("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theCombinedStrategyCursorColumnFollowsTheCaptureMode() throws Exception {
        // 游标策略与游标列合并为一列，CDC 下整列隐藏：日志 CDC 不用游标列。
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, true);
        String cdc = renderDetail();
        String cdcTh = enclosingTag(cdc, "data-role=\"progress-strategy\"", "<th");
        assertThat(cdcTh).contains("is-hidden").contains("data-cdc-visibility=\"non-cdc\"");

        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);
        String polling = renderDetail();
        String pollingTh = enclosingTag(polling, "data-role=\"progress-strategy\"", "<th");
        assertThat(pollingTh).doesNotContain("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theProgressTableUsesFullAndIncrementalStatesInsteadOfLifetimeRows() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, true);

        String page = renderDetail();

        assertThat(page).contains(">Full-load state</th>");
        assertThat(page).contains(">Incremental state</th>");
        assertThat(page).doesNotContain(">Rows changed</th>");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theResetButtonIsHiddenOnTheServerForACdcProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.LOGMINER_CDC, true);

        String page = renderDetail();

        // Reset is meaningless for a CDC project (the server refuses it with
        // error.project.cdc.reset.use.rebaseline). Rendering it visible until the first JS
        // pass is a window in which an admin can click a button that only errors.
        String button = buttonTag(page, "/api/projects/1/reset");
        assertThat(button).contains("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theResetButtonIsVisibleOnTheServerForAPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        String button = buttonTag(page, "/api/projects/1/reset");
        assertThat(button).doesNotContain("is-hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aCacheProjectHasNoRowCountAction() throws Exception {
        stubProject(SyncTargetKind.REDIS, CaptureStrategy.BINLOG_CDC, true);

        String page = renderDetail();

        // Row counts read the source and the JDBC target. A Redis project has no target
        // database, so every target count would come back as an error.
        assertThat(page).doesNotContain("data-role=\"row-count-open\"");
        assertThat(page).doesNotContain("id=\"row-count-modal\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aCacheProjectHasASingleRebuildAction() throws Exception {
        stubProject(SyncTargetKind.REDIS, CaptureStrategy.BINLOG_CDC, true);

        String page = renderDetail();

        // The cache maintenance card owns "rebuild" for a cache target; the generic CDC
        // rebaseline button would be a second entry point for the same destruction.
        assertThat(page).doesNotContain("/api/projects/1/cdc-rebaseline");
        assertThat(page).contains("data-role=\"redis-rebuild\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aCacheProjectWithAStalePollingStrategyExplainsWhyStartIsDisabled() throws Exception {
        // A JDBC -> Redis target switch keeps the stored config, so the row can say REDIS
        // while the strategy is still POLLING. Start rejects it server-side; the page must
        // say why instead of leaving an enabled button that only errors.
        stubProject(SyncTargetKind.REDIS, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        assertThat(buttonTag(page, "/api/projects/1/start"))
                .contains("disabled")
                .contains("only run with log-based change capture");
        assertThat(buttonTag(page, "/api/projects/1/sync-now")).contains("disabled");
    }

    private static String buttonTag(String page, String action) {
        int at = page.indexOf("data-action=\"" + action + "\"");
        assertThat(at).as("button for %s exists", action).isPositive();
        int start = page.lastIndexOf("<button", at);
        int end = page.indexOf(">", at);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(at);
        return page.substring(start, end + 1);
    }

    /** The enclosing {@code <td ...>} element of the first occurrence of {@code marker}. */
    private static String enclosingTag(String page, String marker, String element) {
        int at = page.indexOf(marker);
        assertThat(at).as("marker %s exists", marker).isPositive();
        int start = page.lastIndexOf(element, at);
        int end = page.indexOf(">", at);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(at);
        return page.substring(start, end + 1);
    }

    /**
     * The detail page's live inspections dial the production source. The equivalent APIs are
     * writer-only AND ownership-checked; a viewer or a non-owner opening the page must get
     * the stored state, not the same probe (or pay ~15 s of source queries per tab load).
     */
    @Test
    @WithMockUser(roles = "VIEWER")
    void aViewerDoesNotTriggerLiveSourceReads() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, false);

        mvc.perform(get("/projects/1")).andExpect(status().isOk());

        verify(projectService, never()).listSourceObjects(1L);
        verify(projectService, never()).findUnindexedCursorTables(any(), any());
        verify(projectService, never()).findStringConcatenationWarnings(any());
        verifyNoInteractions(cdcOperationsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminStillGetsTheLiveSourceListing() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, false);

        mvc.perform(get("/projects/1")).andExpect(status().isOk());

        verify(projectService).listSourceObjects(1L);
    }
}
