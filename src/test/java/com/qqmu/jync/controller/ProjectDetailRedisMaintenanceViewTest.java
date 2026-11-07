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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

/**
 * Cache rebuilding belongs to the Redis projection, not to the everyday run-control row.
 *
 * <p>The action is rare, destructive and only meaningful for a REDIS target, so it has to live
 * in the right-hand cache column below the projection card. A JDBC target must never see any of
 * it, and a read-only user must not see the maintenance action at all.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDetailRedisMaintenanceViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;

    @Test
    void aRedisTargetShowsCacheMaintenanceBelowTheProjectionCard() throws Exception {
        stubProject(SyncTargetKind.REDIS, false);

        String page = render(asAdmin());

        int projection = page.indexOf("缓存投影");
        int maintenance = page.indexOf("缓存维护");
        int rebuild = page.indexOf("data-role=\"redis-rebuild\"");

        assertThat(projection).as("projection card is rendered").isGreaterThan(-1);
        assertThat(maintenance).as("maintenance card follows the projection card")
                .isGreaterThan(projection);
        assertThat(rebuild).as("the rebuild action lives in the maintenance card")
                .isGreaterThan(maintenance);
    }

    @Test
    void theSweepOptionIsAnUncheckedOneShotCheckbox() throws Exception {
        stubProject(SyncTargetKind.REDIS, false);

        String page = render(asAdmin());

        Matcher sweep = Pattern
                .compile("<input[^>]*id=\"redis-rebuild-sweep\"[^>]*>")
                .matcher(page);
        assertThat(sweep.find()).as("sweep checkbox is rendered").isTrue();
        assertThat(sweep.group()).as("sweep must default to off")
                .doesNotContain("checked");
        assertThat(page).contains("只删除本项目键前缀下的键");
    }

    @Test
    void aNonRedisTargetNeverSeesTheCacheCards() throws Exception {
        stubProject(SyncTargetKind.JDBC, false);

        String page = render(asAdmin());

        assertThat(page)
                .doesNotContain("缓存投影")
                .doesNotContain("缓存维护")
                .doesNotContain("data-role=\"redis-rebuild\"");
    }

    @Test
    void aViewerSeesTheProjectionButNotTheMaintenanceAction() throws Exception {
        stubProject(SyncTargetKind.REDIS, false);

        String page = render(asViewer());

        assertThat(page).contains("缓存投影");
        assertThat(page)
                .doesNotContain("缓存维护")
                .doesNotContain("data-role=\"redis-rebuild\"");
    }

    @Test
    void aRunningTaskExplainsWhyRebuildIsDisabled() throws Exception {
        stubProject(SyncTargetKind.REDIS, true);

        String page = render(asAdmin());

        assertThat(page).contains("缓存维护").contains("任务运行中不能重建缓存");
    }

    private void stubProject(SyncTargetKind targetKind, boolean enabled) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(enabled);
        project.setTargetKind(targetKind);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of(cursor("EVENTS")));
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        when(projectService.findUnindexedCursorTables(eq(1L), anyList())).thenReturn(Set.of());
    }

    private static SyncProgress cursor(String table) {
        SyncProgress p = new SyncProgress();
        p.setProjectId(1L);
        p.setObjectType(ObjectType.TABLE);
        p.setObjectName(table);
        p.setCursorColumn("UPDATED");
        p.setCursorStrategy("TIMESTAMP");
        return p;
    }

    private String render(RequestPostProcessor principal) throws Exception {
        return mvc.perform(get("/projects/1")
                        .header("Accept-Language", "zh-CN")
                        .with(principal))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor asAdmin() {
        return as("admin", UserRole.ADMIN);
    }

    private static RequestPostProcessor asViewer() {
        return as("view", UserRole.VIEWER);
    }

    private static RequestPostProcessor as(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
