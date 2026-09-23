package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

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
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

/**
 * The settings-page author card: repository links with brand icons plus the author's
 * contact handles. Values come from application.yml so an internal deployment can blank
 * them out; blank entries must drop their row instead of rendering an empty label.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.gitee-url=https://gitee.com/super_rgh/jync",
        "app.contact-qq=817094/2912167928",
        "app.contact-wechat=hua47609"})
class SettingsAuthorCardViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

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

    private String render() throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("1.2.0")
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .build());
        return mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(as(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void authorCardShowsBothRepoLinksAndBothContacts() throws Exception {
        String page = render();

        assertThat(page).contains("项目与作者");
        assertThat(page).contains("https://github.com/vfaner/jync");
        assertThat(page).contains("https://gitee.com/super_rgh/jync");
        assertThat(page).contains("/assets/github.svg");
        assertThat(page).contains("/assets/gitee.ico");
        assertThat(page).contains("817094/2912167928");
        assertThat(page).contains("hua47609");
        // dt tags: the donate dialog has its own 微信 pay tab, so the bare word proves nothing.
        assertThat(page).contains(">微信</dt>");
        assertThat(page).contains(">QQ</dt>");
        assertThat(page).doesNotContain("??settings.");
    }

    @Test
    void repoLinksOpenInANewTab() throws Exception {
        String page = render();

        // Same convention as the nav GitHub icon: new tab, no referrer leakage.
        for (String url : List.of("https://github.com/vfaner/jync",
                "https://gitee.com/super_rgh/jync")) {
            assertThat(page).containsPattern("class=\"repo-link\"\\s+href=\""
                    + java.util.regex.Pattern.quote(url)
                    + "\"\\s+target=\"_blank\"\\s+rel=\"noopener noreferrer\"");
        }
    }

    @Test
    void viewerCanSeeTheAuthorCard() throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .state(VersionInfo.State.UP_TO_DATE).build());

        String page = mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(as(UserRole.VIEWER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("项目与作者");
    }
}
