package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The update dot is part of the shared layout, not of the settings page: admins get it on every
 * page so it survives navigation, armed by the client-side cache after the first check. Viewers
 * get neither the dot nor the request behind it.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class NavUpdateBadgeViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DashboardService dashboardService;
    @MockBean
    private BuildProperties buildProperties;

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String dashboardAs(UserRole role) throws Exception {
        when(dashboardService.collect()).thenReturn(new DashboardService.Stats());
        return mvc.perform(get("/dashboard").locale(Locale.SIMPLIFIED_CHINESE).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminSeesTheHiddenDotOnAnOrdinaryPage() throws Exception {
        String page = dashboardAs(UserRole.ADMIN);

        assertThat(page).contains("id=\"nav-update-badge\"");
        assertThat(page).contains("data-url=\"/api/version/check\"");
        assertThat(page).contains("data-refresh=\"false\"");
        assertThat(page).containsPattern("id=\"nav-update-badge\"[^>]*\\shidden");
    }

    @Test
    void viewerGetsNeitherTheDotNorTheCheck() throws Exception {
        String page = dashboardAs(UserRole.VIEWER);

        assertThat(page).doesNotContain("nav-update-badge");
        assertThat(page).doesNotContain("/api/version/check");
    }
}
