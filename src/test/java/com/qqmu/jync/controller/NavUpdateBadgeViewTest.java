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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.MetricsQueryService;

/**
 * The update dot is part of the shared layout, not of the settings page: admins get it on every
 * page so it survives navigation, armed by the client-side cache after the first check. Viewers
 * get neither the dot nor the request behind it.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class, SystemMetrics.class})
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class NavUpdateBadgeViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private BuildProperties buildProperties;
    @MockitoBean
    private MetricsQueryService metricsQueryService;

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

    /**
     * The dot used to live only inside the 系统管理 dropdown, invisible while it was closed.
     * The trigger now carries a second one so the hint survives the collapsed nav.
     */
    @Test
    void theSystemTriggerAlsoCarriesTheDot() throws Exception {
        String page = dashboardAs(UserRole.ADMIN);

        assertThat(page).contains("id=\"nav-update-badge-top\"");
        assertThat(page).containsPattern(
                "(?s)<button[^>]*class=\"[^\"]*nav-trigger[^\"]*\"[^>]*>.*?"
                + "id=\"nav-update-badge-top\"");
        assertThat(page).contains("nav-update-dot-trigger");
    }

    /**
     * Dashboard is where the new-version dialog opens. It rides on the check the nav badge
     * already performs — no extra request — and reuses the settings-page upgrade dialog.
     */
    @Test
    void theDashboardCarriesTheNewVersionDialogAndItsUpgradeDialog() throws Exception {
        String page = dashboardAs(UserRole.ADMIN);

        assertThat(page).contains("id=\"update-available-modal\"");
        assertThat(page).contains("data-ignore-url=\"/api/version/ignore\"");
        assertThat(page).contains("立即更新");
        assertThat(page).contains("忽略此版本");
        assertThat(page).contains("id=\"upgrade-modal\"");
        assertThat(page).contains("/api/upgrade/start");
        assertThat(page).contains("/api/upgrade/status");
    }

    @Test
    void viewerGetsNeitherTheDotNorTheCheck() throws Exception {
        String page = dashboardAs(UserRole.VIEWER);

        assertThat(page).doesNotContain("nav-update-badge");
        assertThat(page).doesNotContain("/api/version/check");
        assertThat(page).doesNotContain("update-available-modal");
        assertThat(page).doesNotContain("upgrade-modal");
    }
}
