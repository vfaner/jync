package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
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
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The project page must answer "when will this actually run?" before the time comes.
 *
 * <p>"我的 cron 到时间没生效" cannot be checked from a schedule the operator cannot see: a
 * project that is stopped, or whose job failed to register, looks identical to a working one
 * until the fire time passes with nothing happening.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectNextRunViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    @Test
    void aScheduledProjectShowsWhenItWillRunNext() throws Exception {
        stubProject(true);
        when(projectService.nextFireTime(1L))
                .thenReturn(LocalDateTime.of(2026, 10, 22, 17, 55, 0));

        String page = render("zh-CN");

        assertThat(page).contains("data-role=\"next-run\"");
        assertThat(page).contains("2026-10-22 17:55:00");
        // The zone is part of the answer: Quartz evaluates the cron in the server's zone, so a
        // server left on UTC silently shifts a 17:55 schedule by eight hours.
        assertThat(page).contains(java.time.ZoneId.systemDefault().getId());
    }

    @Test
    void aStoppedProjectSaysNoScheduleWillRun() throws Exception {
        stubProject(false);

        String page = render("zh-CN");

        // The most common false alarm: the schedule was saved on a stopped project.
        assertThat(page).contains("data-role=\"next-run\"");
        assertThat(page).contains("未启动");
    }

    @Test
    void theEnglishPageExplainsTheStoppedScheduleToo() throws Exception {
        stubProject(false);

        String page = render("en-US");

        assertThat(page).contains("Not started");
        assertThat(page).doesNotContain("未启动");
    }

    private void stubProject(boolean enabled) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(enabled);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(enabled);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
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
