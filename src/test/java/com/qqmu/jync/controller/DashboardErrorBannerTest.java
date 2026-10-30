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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

/**
 * The failing-tasks banner is a number followed by a noun, and English needs two nouns: one
 * failing task, three failing tasks. The banner used to read "1 Failing tasks" — the count is
 * rendered on its own, so a fixed plural cannot agree with it. The template now picks the form
 * from the count; Chinese uses the same word for both.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DashboardErrorBannerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DashboardService dashboardService;
    @MockBean
    private SystemMetrics systemMetrics;

    @Test
    void oneFailingTaskIsSingular() throws Exception {
        String html = render(1, Locale.ENGLISH);

        assertThat(html).contains("Failing task");
        assertThat(html).doesNotContain("Failing tasks");
    }

    @Test
    void threeFailingTasksArePlural() throws Exception {
        String html = render(3, Locale.ENGLISH);

        assertThat(html).contains("Failing tasks");
    }

    @Test
    void chineseKeepsOneWordForBoth() throws Exception {
        assertThat(render(1, Locale.SIMPLIFIED_CHINESE)).contains("异常任务");
        assertThat(render(4, Locale.SIMPLIFIED_CHINESE)).contains("异常任务");
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
