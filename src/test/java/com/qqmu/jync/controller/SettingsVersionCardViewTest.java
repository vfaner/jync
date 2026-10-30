package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The settings-page version card: green badge when current, amber button when a newer release
 * exists, muted badges for unknown/disabled, and rendered notes for the running version.
 * The check itself happens in {@link VersionService}; here the state is stubbed.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class SettingsVersionCardViewTest {

    private static final String RELEASE_URL =
            "https://github.com/vfaner/jync/releases/tag/v1.2.0";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;
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

    private String render(VersionInfo info) throws Exception {
        return renderAs(UserRole.ADMIN, info);
    }

    private String renderAs(UserRole role, VersionInfo info) throws Exception {
        return renderIn(Locale.SIMPLIFIED_CHINESE, role, info);
    }

    private String renderIn(Locale locale, UserRole role, VersionInfo info) throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(info);
        return mvc.perform(get("/settings").locale(locale)
                        .with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static VersionInfo.VersionInfoBuilder base() {
        return VersionInfo.builder()
                .currentVersion("1.1.1")
                .currentNotesHtml("<h1>Jync v1.1.1</h1><ul><li>变更日志页自动刷新</li></ul>");
    }

    @Test
    void settingsPagePinsAHiddenUpdateDotOnTheSettingsNavLink() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .build());

        // 小圆点挂在「系统信息」导航项右上角，而不是 nav-right 里的一整条横幅。
        assertThat(page).containsPattern(
                "(?s)<a[^>]*class=\"nav-settings[^\"]*\"[^>]*>.*?id=\"nav-update-badge\"");
        assertThat(page).contains("data-url=\"/api/version/check\"");
        assertThat(page).contains("data-refresh=\"true\"");
        assertThat(page).contains("data-label=\"发现新版本：\"");
        assertThat(page).containsPattern("id=\"nav-update-badge\"[^>]*\\shidden");
        assertThat(page).doesNotContain("nav-update-label");
        assertThat(page).doesNotContain("data-role=\"nav-update-version\"");
    }

    @Test
    void upToDateShowsGreenBadgeAndNoUpdateButton() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .releaseUrl("https://github.com/vfaner/jync/releases/tag/v1.1.1")
                .build());

        assertThat(page).contains("系统信息"); // renamed nav/page
        assertThat(page).contains("版本信息"); // card head
        assertThat(page).contains("badge-ok");
        assertThat(page).contains("已是最新");
        assertThat(page).doesNotContain("upgrade-open-btn");
        assertThat(page).contains("v1.1.1");
        assertThat(page).contains("<h1>Jync v1.1.1</h1>");
        assertThat(page).contains("当前版本更新内容");
        assertThat(page).doesNotContain("??settings.");
    }

    /**
     * The bundled notes are prose written in Chinese. Dumping them under the English heading
     * "What's new in this version" gives an English reader a screenful of Chinese; the version
     * and the release page still say everything that matters.
     */
    @Test
    void englishUiReplacesTheChineseNotesWithAPointerToTheReleasePage() throws Exception {
        VersionInfo info = base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .releaseUrl("https://github.com/vfaner/jync/releases/tag/v1.1.1")
                .currentNotesChinese(true)
                .build();

        String en = renderIn(Locale.ENGLISH, UserRole.ADMIN, info);

        assertThat(en).doesNotContain("<h1>Jync v1.1.1</h1>");
        assertThat(en).doesNotContain("变更日志页自动刷新");
        assertThat(en).contains("Release notes for this version are in Chinese.");
        assertThat(en).contains("https://github.com/vfaner/jync/releases/tag/v1.1.1");

        assertThat(renderAs(UserRole.ADMIN, info))
                .as("中文界面照旧显示全文")
                .contains("<h1>Jync v1.1.1</h1>");
    }

    /**
     * {@code ?lang=fr} is applied straight to the resolver by {@code LocaleChangeInterceptor},
     * skipping the narrowing that turns every detected language into English or Chinese. Only
     * two bundles ship, so that request renders English fallback text — and the notes must
     * follow the text, not the locale tag.
     */
    @Test
    void aLocaleWithNoBundleIsTreatedLikeEnglish() throws Exception {
        VersionInfo info = base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .releaseUrl("https://github.com/vfaner/jync/releases/tag/v1.1.1")
                .currentNotesChinese(true)
                .build();

        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(info);
        String fr = mvc.perform(get("/settings").param("lang", "fr").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fr).doesNotContain("<h1>Jync v1.1.1</h1>");
        assertThat(fr).contains("Release notes for this version are in Chinese.");
    }

    /**
     * Update checks can be switched off, and then no release was ever fetched — but the pointer
     * still has to lead somewhere, or the English reader gets a sentence and a dead end.
     */
    @Test
    void theChineseNotesPointerReachesTheReleasesPageWithoutACheckedRelease() throws Exception {
        VersionInfo info = base()
                .state(VersionInfo.State.DISABLED)
                .currentNotesChinese(true)
                .build();

        String en = renderIn(Locale.ENGLISH, UserRole.ADMIN, info);

        assertThat(en).contains("Release notes for this version are in Chinese.");
        assertThat(en).contains("href=\"https://github.com/vfaner/jync/releases\"");
    }

    @Test
    void englishNotesAreShownInTheEnglishUi() throws Exception {
        VersionInfo info = base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1").latestTag("v1.1.1")
                .currentNotesHtml("<h1>Jync v1.1.1</h1><ul><li>Auto-refresh on the change log</li></ul>")
                .currentNotesChinese(false)
                .build();

        String en = renderIn(Locale.ENGLISH, UserRole.ADMIN, info);

        assertThat(en).contains("<h1>Jync v1.1.1</h1>");
        assertThat(en).doesNotContain("Release notes for this version are in Chinese.");
    }

    @Test
    void updateAvailableOffersAdminTheInAppUpgradePlusTheReleasePage() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UPDATE_AVAILABLE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .releaseUrl(RELEASE_URL).latestPublishedDate("2026-09-10")
                .build());

        // Admin's primary action is now the in-app upgrade dialog…
        assertThat(page).contains("一键升级");
        assertThat(page).containsPattern(
                "class=\"btn btn-warn btn-sm\"[^>]*data-open-modal=\"upgrade-modal\"");
        // …with the release page kept as a quieter manual fallback.
        assertThat(page).contains("发布页");
        assertThat(page).containsPattern(
                "class=\"btn btn-ghost btn-sm\"\\s+target=\"_blank\"\\s+rel=\"noopener noreferrer\""
                + "\\s+href=\"" + java.util.regex.Pattern.quote(RELEASE_URL) + "\"");

        assertThat(page).contains("发现新版本");
        assertThat(page).contains("v1.2.0");
        assertThat(page).contains("2026-09-10");
        assertThat(page).doesNotContain("badge-ok");

        // The dialog itself: mirror choice (Gitee pre-checked), wired to the upgrade API,
        // plus the hidden restart-pending badge the poller reveals when a jar is installed.
        assertThat(page).contains("id=\"upgrade-modal\"");
        assertThat(page).containsPattern(
                "name=\"upgrade-source\"[^>]*value=\"GITEE\"[^>]*checked");
        assertThat(page).containsPattern("name=\"upgrade-source\"[^>]*value=\"GITHUB\"");
        assertThat(page).contains("/api/upgrade/start");
        assertThat(page).contains("/api/upgrade/status");
        assertThat(page).contains("/api/upgrade/restart");
        assertThat(page).contains("upgrade-ready-badge");
    }

    /**
     * Same version number, different bytes: the in-app upgrade is still offered, but the words
     * must not announce a "new version" the user is already running — only the package moved.
     */
    @Test
    void aRePublishedArtifactUnderTheSameVersionOffersTheUpgradeAndSaysWhatChanged() throws Exception {
        String page = render(base()
                .currentVersion("1.8.0")
                .state(VersionInfo.State.ARTIFACT_UPDATED)
                .latestVersion("1.8.0").latestTag("v1.8.0")
                .releaseUrl(RELEASE_URL)
                .latestPublishedDate("2026-10-24")
                .latestArtifactDate("2026-10-25")
                .build());

        assertThat(page).contains("一键升级");
        assertThat(page).containsPattern(
                "class=\"btn btn-warn btn-sm\"[^>]*data-open-modal=\"upgrade-modal\"");
        assertThat(page).contains("同一版本号的安装包已重新发布");
        // The date that actually moved is the asset's own upload date, not the release date.
        assertThat(page).contains("2026-10-25");
        assertThat(page).doesNotContain("2026-10-24");
        assertThat(page).doesNotContain("发现新版本：</span>");
        assertThat(page).doesNotContain("badge-ok");
    }

    /**
     * The notice is dismissible by an admin — a self-built jar or a lagging mirror would
     * otherwise keep it lit forever, and a badge that never goes away stops being read.
     * A viewer has no way to act on it and never sees the button.
     */
    @Test
    void onlyAnAdminCanDismissTheArtifactNotice() throws Exception {
        VersionInfo info = base()
                .currentVersion("1.8.0")
                .state(VersionInfo.State.ARTIFACT_UPDATED)
                .latestVersion("1.8.0").latestTag("v1.8.0")
                .latestArtifactDate("2026-10-25")
                .build();

        String admin = render(info);
        assertThat(admin).contains("id=\"artifact-dismiss-btn\"");
        assertThat(admin).contains("data-url=\"/api/version/artifact/dismiss\"");
        assertThat(admin).contains("忽略此提示");

        String viewer = renderAs(UserRole.VIEWER, info);
        assertThat(viewer).doesNotContain("artifact-dismiss-btn");
    }

    /** A genuinely newer release is not a matter of taste, so no dismiss button there. */
    @Test
    void aRealNewVersionOffersNoDismissButton() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UPDATE_AVAILABLE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .releaseUrl(RELEASE_URL)
                .build());

        assertThat(page).doesNotContain("artifact-dismiss-btn");
    }

    /** Same version or not, the pending-restart badge must not claim a "new version". */
    @Test
    void restartPendingBadgeSpeaksOfThePackageNotANewVersion() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1")
                .build());

        assertThat(page).contains("安装包已下载");
        assertThat(page).doesNotContain("新版本已下载");
    }

    /** The nav dot is worded from its own label so it cannot claim a newer version. */
    @Test
    void theNavBadgeCarriesWordingForARePublishedArtifact() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.8.0")
                .build());

        assertThat(page).contains("data-label=\"发现新版本：\"");
        assertThat(page).contains("data-label-artifact=\"安装包已更新\"");
    }

    @Test
    void restartingCopyPromisesTheAutomaticRefresh() throws Exception {
        // 重启由 app.js 轮询 /login/local 完成后自动 reload；文案要如实告诉用户，
        // 不能再让人守着页面手动刷新。/login/local 是本地表单页：外部认证模式下
        // /login 可能 302 到门户，拿它当探测地址会误判重启是否完成。
        String page = render(base()
                .state(VersionInfo.State.UPDATE_AVAILABLE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .releaseUrl(RELEASE_URL)
                .build());

        assertThat(page).contains("完成后页面会自动刷新");
        assertThat(page).contains("data-login-url=\"/login/local\"");
        // 轮询有上限：两分钟后停手并提示手动刷新，不能每秒一直打接口。
        // 文案本身会在内联 JS 字典里做 Unicode 转义，这里只钉住键存在；
        // 中英两份 bundle 的键一致性由 I18nBundleParityTest 兜底。
        assertThat(page).contains("msg.upgrade.restartTimeout");
        // 重启收尾时 app.js 从新版本登录页的字典里取成功文案，字典缺了会显示原始 key。
        assertThat(page).contains("msg.upgrade.success");
    }

    @Test
    void viewerStillGetsThePlainReleasePageButtonAndNoUpgradeDialog() throws Exception {
        String page = renderAs(UserRole.VIEWER, base()
                .state(VersionInfo.State.UPDATE_AVAILABLE)
                .latestVersion("1.2.0").latestTag("v1.2.0")
                .releaseUrl(RELEASE_URL).latestPublishedDate("2026-09-10")
                .build());

        assertThat(page).contains("立即更新");
        assertThat(page).containsPattern(
                "class=\"btn btn-warn btn-sm\"\\s+target=\"_blank\"\\s+rel=\"noopener noreferrer\""
                + "\\s+href=\"" + java.util.regex.Pattern.quote(RELEASE_URL) + "\"");
        assertThat(page).doesNotContain("upgrade-modal");
        assertThat(page).doesNotContain("upgrade-open-btn");
        assertThat(page).doesNotContain("一键升级");
    }

    @Test
    void checkingStateShowsMutedBadgeWhileTheBackgroundCheckRuns() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.CHECKING)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("正在检查更新");
        assertThat(page).doesNotContain("upgrade-open-btn");
    }

    @Test
    void failedCheckShowsMutedBadgeAndDashesForLatest() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.UNKNOWN)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("更新检查失败");
        assertThat(page).doesNotContain("upgrade-open-btn");
    }

    @Test
    void disabledCheckShowsMutedBadge() throws Exception {
        String page = render(base()
                .state(VersionInfo.State.DISABLED)
                .build());

        assertThat(page).contains("badge-mute");
        assertThat(page).contains("已关闭更新检查");
        assertThat(page).doesNotContain("upgrade-open-btn");
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
    void buildTimeStampIsShownNextToCurrentVersion() throws Exception {
        // Same version string can belong to several repackaged jars; the build stamp lets
        // support tell them apart. The clock time is the server's zone; the offset keeps the
        // instant unambiguous, so this assertion is independent of the JVM's time zone.
        when(buildProperties.getTime()).thenReturn(Instant.parse("2026-10-15T13:32:06Z"));
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1")
                .build());

        assertThat(page).containsPattern(
                "构建于 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UTC[+-]\\d{2}:\\d{2}");
    }

    @Test
    void buildTimeWithoutInstantIsHidden() throws Exception {
        // getTime() stays null (build-info carries no time) -> no stamp, no unresolved key.
        String page = render(base()
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.1.1")
                .build());

        assertThat(page).doesNotContain("构建于");
        assertThat(page).doesNotContain("??settings.builtAt");
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
