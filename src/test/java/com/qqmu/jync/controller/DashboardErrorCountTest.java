package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

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
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.MetricsQueryService;

/**
 * 异常任务数不再单挂横幅，而是并进「实时同步任务」卡的副标题，红字醒目。名词跟着数走
 * （英文 1 单数、其余复数；中文两档同词），数字单独成格供 countUp 滚动；没有异常时整段不渲染。
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DashboardErrorCountTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private SystemMetrics systemMetrics;
    @MockitoBean
    private MetricsQueryService metricsQueryService;

    @Test
    void oneFailingTaskIsSingular() throws Exception {
        String html = render(1, Locale.ENGLISH);

        assertThat(html).contains("Failing task");
        assertThat(html).doesNotContain("Failing tasks");
    }

    @Test
    void threeFailingTasksArePlural() throws Exception {
        assertThat(render(3, Locale.ENGLISH)).contains("Failing tasks");
    }

    @Test
    void chineseKeepsOneWordForBoth() throws Exception {
        assertThat(render(1, Locale.SIMPLIFIED_CHINESE)).contains("异常任务");
        assertThat(render(4, Locale.SIMPLIFIED_CHINESE)).contains("异常任务");
    }

    @Test
    void theFailingCountIsRedInTheProjectCard() throws Exception {
        String html = render(3, Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("stat-err").contains("text-danger");
    }

    @Test
    void noFailingTasksShowsNoErrorSegment() throws Exception {
        assertThat(render(0, Locale.SIMPLIFIED_CHINESE)).doesNotContain("stat-err");
    }

    @Test
    void theStandaloneBannerIsGone() throws Exception {
        assertThat(render(3, Locale.ENGLISH)).doesNotContain("alert-warn");
    }

    private String render(int errorTasks, Locale locale) throws Exception {
        DashboardService.Stats stats = new DashboardService.Stats();
        stats.setErrorTaskCount(errorTasks);
        stats.setRecentChanges(List.of());
        when(dashboardService.collect()).thenReturn(stats);
        when(systemMetrics.snapshot()).thenReturn(SystemMetrics.Snapshot.UNKNOWN);
        return mvc.perform(get("/dashboard").locale(locale).with(asAdmin()))
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
