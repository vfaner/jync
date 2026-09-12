package com.synctool.controller;

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

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.service.task.SyncLockService;
import com.synctool.service.task.SyncScheduler;
import com.synctool.service.version.VersionInfo;
import com.synctool.service.version.VersionService;
import com.synctool.service.auth.SyncUserDetails;

/**
 * The settings-page version card: green badge when current, amber button when a newer release
 * exists, muted badges for unknown/disabled, and rendered notes for the running version.
 * The check itself happens in {@link VersionService}; here the state is stubbed.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/synctool")
class SettingsVersionCardViewTest {

    private static final String RELEASE_URL =
            "https://github.com/vfaner/synctool/releases/tag/v1.2.0";

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

    private String render(VersionInfo info) throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(info);
        return mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(as(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static VersionInfo.VersionInfoBuilder base() {
        return VersionInfo.builder()
                .currentVersion("1.1.1")
                .currentNotesHtml("<h1>SyncTool v1.1.1</h1><ul><li>变更日志页自动刷新</li></ul>");
    }

    @Test
    void upToDateShowsGreenBadgeAndNoUpdateButton() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .releaseUrl("https://github.com/vfaner/synctool/releases/tag/v1.1.1")
                .build());

        assertThat(page).contains("系统信息"); // renamed nav/page
        assertThat(page).contains("版本信息"); // card head
        assertThat(page).contains("badge-ok");
        assertThat(page).contains("已是最新");
        assertThat(page).doesNotContain("btn-warn");
        assertThat(page).contains("v1.1.1");
        assertThat(page).contains("<h1>SyncTool v1.1.1</h1>");
        assertThat(page).contains("当前版本更新内容");
        assertThat(page).doesNotContain("??settings.");
    }

    @Test
    void updateAvailableShowsAmberButtonToTheReleasePage() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UPDATE_AVAILABLE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .releaseUrl(RELEASE_URL).latestPublishedDate("2026-09-10")
                .build());

        assertThat(page).contains("btn-warn");
        assertThat(page).contains("立即更新");
        assertThat(page).contains("发现新版本");
        assertThat(page).contains("v1.2.0");
        assertThat(page).contains("2026-09-10");
        // The button is a plain navigation action, in a new tab — no in-app download/restart.
        // Thymeleaf resolves th:href where the attribute stands (source whitespace may vary).
        assertThat(page).containsPattern(
                "class=\"btn btn-warn btn-sm\"\\s+target=\"_blank\"\\s+rel=\"noopener noreferrer\""
                + "\\s+href=\"" + java.util.regex.Pattern.quote(RELEASE_URL) + "\"");
        assertThat(page).doesNotContain("badge-ok");
    }

    @Test
    void checkingStateShowsMutedBadgeWhileTheBackgroundCheckRuns() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.CHECKING)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("正在检查更新");
        assertThat(page).doesNotContain("btn-warn");
    }

    @Test
    void failedCheckShowsMutedBadgeAndDashesForLatest() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UNKNOWN)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("更新检查失败");
        assertThat(page).doesNotContain("btn-warn");
    }

    @Test
    void disabledCheckShowsMutedBadge() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.DISABLED)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("已关闭更新检查");
        assertThat(page).doesNotContain("btn-warn");
    }

    @Test
    void devBuildWithoutVersionHidesHeaderAndFooterVersion() throws Exception {
        // No BuildProperties bean in this slice -> appVersion is null -> version markup hidden.
        String page = render(base()
                .currentVersion(null)
                .state(VersionInfo.State.UNKNOWN)
                .build());

        assertThat(page).contains("开发构建");
        assertThat(page).doesNotContain("logo-ver");
        assertThat(page).doesNotContain("foot-ver");
    }

    @Test
    void viewerCanReadTheSettingsPage() throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(base()
                .state(VersionInfo.State.UP_TO_DATE).latestVersion("1.1.1").build());

        mvc.perform(get("/settings").locale(Locale.SIMPLIFIED_CHINESE).with(as(UserRole.VIEWER)))
                .andExpect(status().isOk());
    }
}
