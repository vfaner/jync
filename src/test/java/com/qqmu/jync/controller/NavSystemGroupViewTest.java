package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * 系统信息 became 系统管理, a group holding 系统配置 (admin only) and 系统信息 (everyone).
 *
 * <p>The settings entry keeps its {@code nav-settings} class and the update badge: the badge is
 * loaded on every page, and moving the entry into a dropdown must not take it off the nav.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class NavSystemGroupViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DatabaseConfigService databaseConfigService;
    @MockitoBean
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

    private String render(UserRole role) throws Exception {
        when(databaseConfigService.findPageByRole(any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());
        return mvc.perform(get("/databases").locale(Locale.SIMPLIFIED_CHINESE).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminSeesBothSystemPagesUnderOneGroup() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("系统管理", "系统配置", "系统信息");
        assertThat(page).containsPattern("href=\"/system-config\"[\\s\\S]{0,200}>系统配置<");
        assertThat(page).containsPattern("href=\"/settings\"[\\s\\S]{0,200}>系统信息<");
    }

    @Test
    void viewerSeesOnlyTheReadOnlySystemPage() throws Exception {
        String page = render(UserRole.VIEWER);

        assertThat(page).contains("系统管理", "系统信息");
        assertThat(page).doesNotContain("href=\"/system-config\"", "系统配置");
    }

    @Test
    void theUpdateBadgeStaysOnTheSystemInfoEntry() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).containsPattern(
                "(?s)<a[^>]*class=\"[^\"]*nav-settings[^\"]*\"[^>]*>.*?id=\"nav-update-badge\"");
    }

    @Test
    void theSystemGroupIsNotActiveOnAConfigurationPage() throws Exception {
        String page = render(UserRole.ADMIN);

        // /databases belongs to the configuration group; two highlighted groups would make the
        // nav claim the user is in two places at once.
        assertThat(page).doesNotContain("nav-system is-active");
    }
}
