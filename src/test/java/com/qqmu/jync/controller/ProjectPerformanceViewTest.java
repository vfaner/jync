package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.metrics.ChartSeries;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;
import com.qqmu.jync.service.metrics.ProjectMetricsView;

/**
 * The task performance page is open to every signed-in account and carries only this task's
 * own curves. The process resources used to sit here too; they describe the JVM every task
 * shares, so they moved to the dashboard — one card, one poll, instead of one copy per project.
 */
@WebMvcTest(ProjectPerformanceController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectPerformanceViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private MetricsQueryService metricsService;

    @Test
    void aViewerSeesThroughput() throws Exception {
        givenProject();

        String html = page(UserRole.VIEWER);

        assertThat(html).contains("id=\"chart-rows\"")
                .contains("data-metrics-url=\"/projects/1/metrics\"");
    }

    /** 进程资源搬到看板了：项目页对两种角色都不再出现这块，也不再有它自己的图。 */
    @Test
    void neitherRoleGetsAProcessBlockOnTheProjectPage() throws Exception {
        givenProject();

        for (UserRole role : UserRole.values()) {
            String html = page(role);
            assertThat(html).as(role.name())
                    .doesNotContain("id=\"process-block\"")
                    .doesNotContain("data-chart=\"cpu\"");
        }
    }

    @Test
    void thePageTellsTheScriptWhereToPoll() throws Exception {
        givenProject();

        String html = page(UserRole.VIEWER);

        assertThat(html).contains("data-metrics-url=\"/projects/1/metrics\"");
        assertThat(html).contains("data-range=");
    }

    /**
     * 延迟是毫秒数：1,135,000 ms 既读不出量级，也让人觉得任务慢得没边。卡片和曲线改成
     * 按时间单位换算，和变更日志的耗时同一种读法——超过一秒就是「多少秒多少毫秒」。
     */
    @Test
    void theLagCardAndChartAskTheScriptForDurationUnits() throws Exception {
        givenProject();

        String html = page(UserRole.VIEWER);

        assertThat(html).containsPattern("data-current=\"lag\"[^>]*data-format=\"duration\"");
        assertThat(html).containsPattern("data-chart=\"lag\"[^>]*data-duration=\"true\"");
    }

    /**
     * 状态标签的文字由 i18n 决定：脚本只拿这些 data-* 去查，不能自己把 "RUNNING" 写进
     * 中文界面。
     */
    @Test
    void theStatusChipCarriesTheLocalizedLabelsForTheScript() throws Exception {
        givenProject();

        String html = page(UserRole.VIEWER);

        assertThat(html).contains("data-running=")
                .contains("data-stopped=")
                .contains("data-error=");
    }

    /**
     * 环形 / 条形图的宿主必须自己定位。.chart-empty 是 absolute + inset:0，宿主不定位时
     * 占位说明会逃到视口、盖住整页并吞掉 1 小时 / 7 天按钮的点击。
     */
    @Test
    void theChangePanelsArePositionedHosts() throws Exception {
        givenProject();

        String html = page(UserRole.VIEWER);

        assertThat(html).contains("class=\"chart-host\" data-chart-share")
                .contains("class=\"chart-host\" data-chart-hot");
    }

    /**
     * 进程曲线只能从看板那个管理员专属的接口来。项目页的 JSON 若还夹带 CPU / 堆 / GC，
     * 只读账号换一个地址就能绕开看板的限制。
     */
    @Test
    void theJsonCarriesNoProcessSeriesForEitherRole() throws Exception {
        givenProject();

        for (UserRole role : UserRole.values()) {
            mvc.perform(get("/projects/1/metrics?range=1h").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.series[*].key", hasItem("rows")))
                    .andExpect(jsonPath("$.series[*].key", not(hasItem("cpu"))))
                    .andExpect(jsonPath("$.current.cpu").doesNotExist());
        }
    }

    @Test
    void theRangeParameterReachesTheQuery() throws Exception {
        givenProject();

        mvc.perform(get("/projects/1/metrics?range=7d").with(as(UserRole.VIEWER)))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(metricsService)
                .project(eq(1L), eq(MetricsRange.LAST_WEEK));
    }

    @Test
    void aMissingProjectIsNotFoundRatherThanAnEmptyChart() throws Exception {
        when(projectService.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/projects/99/metrics").with(as(UserRole.VIEWER)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/projects/99/performance").with(as(UserRole.VIEWER)))
                .andExpect(status().is3xxRedirection());
    }

    private String page(UserRole role) throws Exception {
        return mvc.perform(get("/projects/1/performance").with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void givenProject() {
        Project project = new Project();
        project.setId(1L);
        project.setName("orders-sync");
        project.setSourceDbId(2L);
        project.setTargetDbId(3L);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(connection(2L, "src")));
        when(databaseConfigService.findById(3L)).thenReturn(Optional.of(connection(3L, "tgt")));
        when(metricsService.project(eq(1L), any())).thenAnswer(invocation -> {
            List<ChartSeries> series = new ArrayList<>();
            series.add(new ChartSeries("rows", "Rows", List.of()));
            series.add(new ChartSeries("lag", "Lag", List.of()));
            return new ProjectMetricsView(null, "RUNNING", series, Map.of(), List.of(), List.of());
        });
    }

    private DatabaseConfig connection(long id, String name) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        com.qqmu.jync.service.auth.SyncUserDetails principal =
                new com.qqmu.jync.service.auth.SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
