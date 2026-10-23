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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * The navigation groups the two families of pages (configuration and logs) instead of listing
 * every entry top-level. Viewers still see only the entries they can actually open.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class NavMenuGroupsViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService databaseConfigService;
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

    private String render(UserRole role) throws Exception {
        when(databaseConfigService.findPageByRole(any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());
        return mvc.perform(get("/databases")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminSeesBothGroupsWithEveryChild() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("配置管理", "日志管理");
        assertThat(page).contains("href=\"/databases\"", "href=\"/ai\"", "href=\"/oa/settings\"");
        assertThat(page).contains("href=\"/change-logs\"", "href=\"/audits\"",
                "href=\"/system-logs\"");
        assertThat(page).contains("审计日志", "系统日志", "OA 配置");
    }

    @Test
    void viewerOnlySeesEntriesTheyCanOpen() throws Exception {
        String page = render(UserRole.VIEWER);

        assertThat(page).contains("配置管理", "日志管理");
        assertThat(page).contains("href=\"/databases\"", "href=\"/ai\"");
        assertThat(page).contains("href=\"/change-logs\"", "href=\"/system-logs\"");
        assertThat(page).doesNotContain("href=\"/oa/settings\"", "href=\"/audits\"");
        assertThat(page).doesNotContain("审计日志", "OA 配置");
    }

    @Test
    void theConfigurationGroupIsMarkedActiveOnAConfigurationPage() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).containsPattern("nav-links-group is-active[\\s\\S]{0,400}配置管理");
        // Exactly one group carries the active marker; the logs group is not highlighted
        // just because a configuration page happens to be open.
        assertThat(page.split("nav-links-group is-active", -1)).hasSize(2);
    }
}
