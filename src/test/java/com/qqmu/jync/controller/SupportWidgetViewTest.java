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
                .contains("data-tool-project_status=")
                .contains("data-label-greeting=\"Hi, I")
                .contains("placeholder=\"Type a message\"")
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
