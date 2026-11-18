package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

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
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportDraftStore;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.projection.redis.RedisTargetService;

@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectDraftPrefillTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private DatabaseConfigService databaseConfigService;
    @MockitoBean private CdcOperationsService cdcOperationsService;
    @MockitoBean private CdcConsumerManager cdcConsumerManager;
    @MockitoBean private CdcPositionStore cdcPositionStore;
    @MockitoBean private RedisTargetService redisTargetService;
    @MockitoBean private SupportDraftStore draftStore;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("x");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void aValidTokenPrefillsTheIdentityAndConnections() throws Exception {
        Project project = new Project();
        project.setName("orders-sync");
        project.setSourceDbId(1L);
        project.setTargetDbId(2L);
        project.setTargetKind(SyncTargetKind.JDBC);
        when(draftStore.take("tok", "admin")).thenReturn(Optional.of(
                new SupportDraftStore.Draft("admin", project, "建议按天增量", null)));
        when(databaseConfigService.findByRole(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(redisTargetService.findAll()).thenReturn(List.of());

        String page = mvc.perform(get("/projects/new").param("draft", "tok").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("value=\"orders-sync\"");
        assertThat(page).contains("建议按天增量");
    }

    @Test
    void anExpiredTokenFallsBackToABlankFormWithANotice() throws Exception {
        when(draftStore.take("gone", "admin")).thenReturn(Optional.empty());
        when(databaseConfigService.findByRole(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(redisTargetService.findAll()).thenReturn(List.of());

        String page = mvc.perform(get("/projects/new").param("draft", "gone").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The message must be translated now that the key exists; a missing key would render
        // as ??support.draft.expired_zh_CN?? and leak the raw name into the page.
        assertThat(page).doesNotContain("support.draft.expired");
        assertThat(page).doesNotContain("draftSuggestion");
    }
}
