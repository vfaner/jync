package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.service.settings.AiAvailability;

/**
 * The floating assistant renders only for writers, and only while AI is usable.
 *
 * <p>The viewer case reads the project list rather than the create form: {@code /projects/new}
 * is writer-only and would answer 403 for a VIEWER, so it could not tell "widget hidden" from
 * "page refused".
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class SupportWidgetViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;
    @MockitoBean private SupportAssistantService supportAssistant;
    @MockitoBean private AiAvailability aiAvailability;

    private static RequestPostProcessor asRole(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("x");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String createForm(UserRole role) throws Exception {
        when(databaseConfigService.findByRole(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(redisTargetService.findAll()).thenReturn(List.of());
        return mvc.perform(get("/projects/new").with(asRole(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String projectList(UserRole role) throws Exception {
        return mvc.perform(get("/projects").with(asRole(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminWithAiAvailableSeesTheEntryAndItsTranslations() throws Exception {
        when(aiAvailability.isHardDisabled()).thenReturn(false);
        when(supportAssistant.isAvailable()).thenReturn(true);

        String html = createForm(UserRole.ADMIN);

        assertThat(html).contains("id=\"support-widget\"")
                .contains("/js/support.js")
                .contains("data-action=\"/api/support/chat\"")
                // 切页面后用来重接正在进行的回答。
                .contains("data-run-url=\"/api/support/runs\"")
                .contains("data-tool-project_status=")
                .contains("data-tool-project_table_status=")
                .contains("data-tool-update_project_params=")
                .contains("data-tool-update_project_cursor=")
                .contains("data-tool-update_project_schedule=")
                .contains("data-tool-update_connection_params=")
                .contains("data-tool-connection_details=")
                .contains("data-tool-recent_failures=")
                .contains("data-tool-diagnose_project=")
                .contains("data-label-step-queued=\"Queued:\"")
                .contains("data-label-step-running=\"Running:\"")
                .contains("data-label-step-done=\"Done:\"")
                .contains("data-label-step-failed=\"Failed:\"")
                .contains("data-label-greeting=\"Hi, I")
                .contains("placeholder=\"Ask a question or paste a full error log (long logs keep head and tail)\"")
                // 日志整段粘贴常有两万字符；上限放宽，服务端再保留首尾各一段。
                .contains("maxlength=\"20000\"")
                .doesNotContain("??");
    }

    @Test
    void viewerNeverSeesTheEntry() throws Exception {
        when(aiAvailability.isHardDisabled()).thenReturn(false);
        when(supportAssistant.isAvailable()).thenReturn(true);

        assertThat(projectList(UserRole.VIEWER)).doesNotContain("id=\"support-widget\"");
    }

    @Test
    void aiOffRemovesTheEntryEvenForAdmins() throws Exception {
        when(aiAvailability.isHardDisabled()).thenReturn(false);
        when(supportAssistant.isAvailable()).thenReturn(false);

        assertThat(createForm(UserRole.ADMIN)).doesNotContain("id=\"support-widget\"");
    }

    @Test
    void aHardDisabledDeploymentRemovesTheEntry() throws Exception {
        when(aiAvailability.isHardDisabled()).thenReturn(true);
        when(supportAssistant.isAvailable()).thenReturn(true);

        assertThat(createForm(UserRole.ADMIN)).doesNotContain("id=\"support-widget\"");
    }
}
