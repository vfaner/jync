package com.qqmu.jync.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The per-table cursor override form.
 *
 * <p>The guard itself lives in the service (see {@code ProjectServiceCursorColumnTest}); what
 * this pins is that the page reports the refusal instead of redirecting as if the change had
 * been saved.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectCursorViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

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

    @Test
    void aRefusedChangeShowsTheServicesReason() throws Exception {
        doThrow(new IllegalStateException("error.project.stop.before.config"))
                .when(projectService).saveCursorColumn(1L, "orders", "created_at");

        mvc.perform(post("/projects/1/cursor").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("cursorColumn", "created_at"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/1"))
                .andExpect(flash().attribute("error", "error.project.stop.before.config"));
    }

    @Test
    void aSavedChangeIsConfirmed() throws Exception {
        mvc.perform(post("/projects/1/cursor").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("cursorColumn", "created_at"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "msg.cursor.saved"));

        verify(projectService).saveCursorColumn(1L, "orders", "created_at");
    }

    @Test
    void aBlankColumnIsForwardedForAutoDetection() throws Exception {
        mvc.perform(post("/projects/1/cursor").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("cursorColumn", ""))
                .andExpect(status().is3xxRedirection());

        // Blank is the "back to auto-detection" submission, not "leave as is".
        verify(projectService).saveCursorColumn(1L, "orders", "");
    }
}
