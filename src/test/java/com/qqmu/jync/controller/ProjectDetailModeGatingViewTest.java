package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
        } else {
            when(projectService.findProgress(1L)).thenReturn(List.of());
        }
        return project;
    }

    private String renderDetail() throws Exception {
        return mvc.perform(get("/projects/1").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void aReadOnlyAccountNeverGetsTheCursorOverrideFieldInTheJavascriptHook() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        // The cell is hidden on the server and carries no data-role, so app.js's strategy
        // switch cannot reveal it; a viewer never sees an input whose POST would be a 403.
        String cell = enclosingTag(page, "data-role=\"cursor-form\"", "<td");
        assertThat(cell).contains("is-hidden").doesNotContain("data-role=\"polling-only\"");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminKeepsTheCursorOverrideFieldForAPollingProject() throws Exception {
        stubProject(SyncTargetKind.JDBC, CaptureStrategy.POLLING, true);

        String page = renderDetail();

        String cell = enclosingTag(page, "data-role=\"cursor-form\"", "<td");
        assertThat(cell).contains("data-role=\"polling-only\"").doesNotContain("is-hidden");
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
}
