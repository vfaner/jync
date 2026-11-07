package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import com.qqmu.jync.model.ObjectType;
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
 * The project page marks the tables whose cursor column has no index at the source.
 *
 * <p>The sync itself only says so in its log, once, and the operator of the page is the one
 * who can create the index. The mark has to be per table — a page-wide banner would send the
 * reader looking through a whole schema — and an unreadable source must cost the hint, not
 * the page.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailCursorIndexViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;

    @Test
    void theUnindexedCursorTableIsMarkedOnThePage() throws Exception {
        stubProject();
        when(projectService.findUnindexedCursorTables(eq(1L), anyList()))
                .thenReturn(Set.of("EVENTS"));

        String page = render("zh-CN");

        // Two attributes on that one row: aria-label for screen readers and data-tip for
        // the hover bubble. The hint belongs to the flagged row, not to the table that
        // already has its index, so it appears exactly on one icon.
        assertThat(occurrences(page, "该游标列在源库没有索引")).isEqualTo(2);
        assertThat(occurrences(page, "data-tip=\"")).isEqualTo(1);
        assertThat(page).contains("EVENTS");
    }

    @Test
    void aFullyIndexedSchemaShowsNoHint() throws Exception {
        stubProject();
        when(projectService.findUnindexedCursorTables(eq(1L), anyList())).thenReturn(Set.of());

        assertThat(render("zh-CN")).doesNotContain("该游标列在源库没有索引");
    }

    @Test
    void aFailedInspectionStillRendersThePage() throws Exception {
        stubProject();
        when(projectService.findUnindexedCursorTables(eq(1L), anyList()))
                .thenThrow(new IllegalStateException("error.connection.failed"));

        // Advisory: an unreachable source is the page's own warning (sourceWarning), not a 500.
        assertThat(render("zh-CN")).doesNotContain("该游标列在源库没有索引");
    }

    @Test
    void theIndexHintRidesTheAppsOwnTipBubble() throws Exception {
        stubProject();
        when(projectService.findUnindexedCursorTables(eq(1L), anyList()))
                .thenReturn(Set.of("EVENTS"));

        String page = render("zh-CN");

        // The browser's own tooltip (title attribute or SVG <title>) waits ~1s and cannot
        // be styled. The hint is marked data-tip instead and painted by app.js as the same
        // dark bubble the performance knobs use, instantly on hover or keyboard focus.
        assertThat(page).doesNotContain("title=\"该游标列在源库没有索引");
        assertThat(page).contains("data-tip=\"该游标列在源库没有索引：每次增量读取都会全表扫描，建议在该列上建索引。\"");
        assertThat(page).containsPattern("(?s)data-tip=\"[^\"]*没有索引[^\"]*\">\\s*"
                + "<svg[^>]*>\\s*<use href=\"#exclamation-triangle-fill\"");
    }

    @Test
    void theStrategyWarningRidesTheSameTipBubble() throws Exception {
        stubProject();
        when(projectService.findProgress(1L))
                .thenReturn(List.of(cursor("EVENTS", "NONE")));

        String page = render("zh-CN");

        assertThat(page).doesNotContain("title=\"该表的策略无法检测已有行的更新。\"");
        assertThat(page).contains("data-tip=\"该表的策略无法检测已有行的更新。\"");
    }

    @Test
    void theEnglishPageExplainsTheMissingIndexToo() throws Exception {
        stubProject();
        when(projectService.findUnindexedCursorTables(eq(1L), anyList()))
                .thenReturn(Set.of("EVENTS"));

        String page = render("en-US");

        assertThat(page).contains("no index at the source");
        assertThat(page).doesNotContain("该游标列在源库没有索引");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private void stubProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L))
                .thenReturn(List.of(cursor("EVENTS"), cursor("LOGS")));
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
    }

    private static SyncProgress cursor(String table) {
        return cursor(table, "TIMESTAMP");
    }

    private static SyncProgress cursor(String table, String strategy) {
        SyncProgress p = new SyncProgress();
        p.setProjectId(1L);
        p.setObjectType(ObjectType.TABLE);
        p.setObjectName(table);
        p.setCursorColumn("UPDATED");
        p.setCursorStrategy(strategy);
        return p;
    }

    private String render(String acceptLanguage) throws Exception {
        return mvc.perform(get("/projects/1")
                        .header("Accept-Language", acceptLanguage)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

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
}
