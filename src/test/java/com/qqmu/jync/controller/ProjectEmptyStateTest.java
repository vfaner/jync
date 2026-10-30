package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The first-run empty state used to say the same thing twice — the heading read
 * "No sync tasks yet. Create one to begin." and the line under it, "Create one to begin.",
 * repeated the second half word for word. The heading now names the state, the line gives the
 * next step, once each.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectEmptyStateTest {

    private static final String HINT = "Create one to begin.";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private DatabaseConfigService databaseConfigService;

    @MockBean
    private CdcOperationsService cdcOperationsService;

    @MockBean
    private CdcConsumerManager cdcConsumerManager;

    @MockBean
    private CdcPositionStore cdcPositionStore;

    @Test
    void theEmptyStateSaysItOnce() throws Exception {
        when(projectService.count()).thenReturn(0L);
        when(projectService.findPage(any(Integer.class), any(Integer.class))).thenReturn(List.of());
        when(projectService.findTasks(any())).thenReturn(Map.of());
        when(projectService.loadConfig(any())).thenReturn(new SyncConfig());

        String html = mvc.perform(get("/projects").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(count(html, HINT)).as("提示句只应出现一次").isEqualTo(1);
        assertThat(html).contains("No sync tasks yet.");
    }

    private static int count(String text, String needle) {
        int hits = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
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
