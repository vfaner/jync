package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The 运行时信息 card names the machine the numbers elsewhere describe.
 *
 * <p>It is also where the resource gauges used to live; they moved to the dashboard as a bare
 * row, so this page must not carry a polling block any more.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "sync.reconcile-interval-ms=30000",
        "spring.datasource.url=jdbc:h2:file:./data/jync",
        "spring.datasource.username=sa"
})
class SettingsRuntimeCardViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SyncScheduler scheduler;
    @MockitoBean
    private SyncLockService lockService;
    @MockitoBean
    private VersionService versionService;
    @MockitoBean
    private RequireHttpsResolver requireHttpsResolver;
    @MockitoBean
    private CryptoUtil cryptoUtil;

    private static final RequireHttpsResolver.Effective HTTPS_OFF =
            new RequireHttpsResolver.Effective(false, RequireHttpsResolver.Source.DEFAULT);

    private String render(Locale locale) throws Exception {
        return render(locale, false, HTTPS_OFF);
    }

    private String render(Locale locale, boolean secure) throws Exception {
        return render(locale, secure, HTTPS_OFF);
    }

    private String render(Locale locale, boolean secure,
                          RequireHttpsResolver.Effective https) throws Exception {
        return render(locale, secure, https, UserRole.ADMIN);
    }

    private String render(Locale locale, UserRole role) throws Exception {
        return render(locale, false, HTTPS_OFF, role);
    }

    private String render(Locale locale, boolean secure,
                          RequireHttpsResolver.Effective https, UserRole role) throws Exception {
        when(requireHttpsResolver.effective()).thenReturn(https);
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("1.8.1")
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.8.1").latestTag("v1.8.1")
                .build());

        return mvc.perform(get("/settings").locale(locale).secure(secure).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRuntimeCardNamesTheOperatingSystem() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("操作系统").contains(System.getProperty("os.name"));
    }

    @Test
    void englishRendersTheEnglishWording() throws Exception {
        String html = render(Locale.ENGLISH);

        assertThat(html).contains("Operating system").doesNotContain("操作系统");
    }

    @Test
    void theMetadataDatabaseCardSitsInTheTopSummaryRow() throws Exception {
        // Read-only facts about the running instance live here; the diagnostics download is an
        // administrator action and moved to 系统配置.
        String html = render(Locale.SIMPLIFIED_CHINESE);

        int projectAuthor = html.indexOf("项目与作者");
        int metadata = html.indexOf("<span>元数据库</span>");
        int supported = html.indexOf("支持的数据库");

        assertThat(projectAuthor).isGreaterThan(-1);
        assertThat(metadata).as("管理员必须能看到元数据库信息").isGreaterThan(-1);
        assertThat(supported).isGreaterThan(-1);
        assertThat(metadata)
                .as("元数据库卡放在上方摘要区、与「项目与作者」同排，而不是页面末尾")
                .isGreaterThan(projectAuthor).isLessThan(supported);
        assertThat(html).contains("jdbc:h2:file:./data/jync", "连接密钥")
                .contains("正在使用专用密钥");
        // The metadata card leads the right column; the longer sync-defaults card follows it.
        assertThat(html.indexOf("<span>同步默认值</span>")).isGreaterThan(metadata);
    }

    @Test
    void theDefaultCryptoKeyIsCalledOut() throws Exception {
        when(cryptoUtil.isUsingDefaultKey()).thenReturn(true);
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("正在使用内置默认密钥");
    }

    @Test
    void viewersDoNotSeeTheMetadataDatabaseCard() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, UserRole.VIEWER);

        assertThat(html).doesNotContain("连接密钥", "<span>元数据库</span>");
    }

    @Test
    void theDiagnosticsBundleIsNoLongerOnThisPage() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).doesNotContain("下载诊断包")
                .doesNotContain("/api/diagnostics/bundle");
    }

    @Test
    void theRuntimeCardShowsTheSpringBootVersion() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(SpringBootVersion.getVersion()).isNotBlank();
        assertThat(html)
                .contains("Spring Boot")
                .containsPattern("(?s)Spring Boot.{0,300}?"
                        + Pattern.quote(SpringBootVersion.getVersion()));
    }

    @Test
    void theRuntimeCardShowsTheEmbeddedTomcatVersion() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(ServerInfo.getServerNumber()).isNotBlank();
        assertThat(html)
                .contains("Tomcat")
                .containsPattern("(?s)Tomcat.{0,300}?"
                        + Pattern.quote(ServerInfo.getServerNumber()));
    }

    @Test
    void theRuntimeCardShowsWhenTheApplicationStarted() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        // A real clock value, not a placeholder: the row is only useful if it carries a time.
        assertThat(html).containsPattern("(?s)启动时间.{0,500}?\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void aPlainHttpRequestIsLabelledAsSuchAndPointsAtTheFix() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("连接加密").contains("HTTP 明文")
                .contains("JYN_REQUIRE_HTTPS=true");
    }

    @Test
    void anHttpsRequestIsLabelledAsEncrypted() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, true);

        assertThat(html).contains("HTTPS 已加密")
                // The warning belongs to the plaintext case only.
                .doesNotContain("当前连接走明文 HTTP");
    }

    @Test
    void theHttpsRowSpeaksEnglishOnTheEnglishUi() throws Exception {
        String html = render(Locale.ENGLISH, true);

        assertThat(html).contains("Connection security").contains("HTTPS encrypted")
                .doesNotContain("连接加密");
    }

    @Test
    void theHttpsRowNamesThePageAsTheSourceOfTheSwitch() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, false,
                new RequireHttpsResolver.Effective(true, RequireHttpsResolver.Source.DATABASE));

        assertThat(html).contains("页面配置").contains("/system-config");
    }

    @Test
    void aConfigurationPinnedSwitchSaysSoInstead() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE, false,
                new RequireHttpsResolver.Effective(false, RequireHttpsResolver.Source.CONFIG));

        assertThat(html).contains("配置文件").doesNotContain("页面配置");
    }

    @Test
    void theResourceGaugesAreNoLongerOnThisPage() throws Exception {
        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).doesNotContain("resource-gauges");
    }

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
}
