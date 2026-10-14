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
        "app.contact-qq-group=426669837",
        "app.contact-email=vfaner@88.com",
        "app.contact-wechat=qqmu66",
        "app.site-url=https://jync.qqmu.com"})
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
        // 两个 QQ 号拆成各自独立的行，不再拼在一个格子里。
        assertThat(page).contains("817094");
        assertThat(page).contains("2912167928");
        assertThat(page).doesNotContain("817094/2912167928");
        assertThat(page).contains("426669837");
        assertThat(page).contains("vfaner@88.com");
        assertThat(page).contains("qqmu66");
        // dt tags: the donate dialog has its own 微信 pay tab, so the bare word proves nothing.
        assertThat(page).contains(">微信</dt>");
        assertThat(page).contains(">QQ</dt>");
        assertThat(page).contains(">QQ群</dt>");
        assertThat(page).contains(">邮箱</dt>");
        assertThat(page).doesNotContain("??settings.");
    }

    @Test
    void qqAccountsAndGroupAreDeepLinksIntoTheQqApp() throws Exception {
        String page = render();

        // 个人 QQ：tencent://message 直接唤起 QQ 并打开与该账号的临时会话（& 在 HTML 中输出为 &amp;）。
        for (String qq : List.of("817094", "2912167928")) {
            assertThat(page).containsPattern(java.util.regex.Pattern.quote(
                    "tencent://message/?uin=" + qq + "&amp;Site=&amp;Menu=yes"));
            assertThat(page).containsPattern("<a[^>]*tencent://message/\\?uin=" + qq
                    + "[^>]*>[^<]*" + qq + "[^<]*</a>");
        }
        // QQ 群：mqqapi 群资料卡，已在群内可直接进群聊。
        assertThat(page).containsPattern(java.util.regex.Pattern.quote(
                "mqqapi://card/show_pslcard?src_type=internal&amp;version=1"
                        + "&amp;groupcode=426669837"));
        // 邮箱：mailto 唤起本地邮件客户端。
        assertThat(page).containsPattern("<a[^>]*href=\"mailto:vfaner@88\\.com\"[^>]*>"
                + "[^<]*vfaner@88\\.com[^<]*</a>");
    }

    @Test
    void contactsFillTheTwoColumnGridInQqsGroupEmailWechatOrder() throws Exception {
        String page = render();

        // 双列网格里依次：(QQ①,QQ②) / (QQ群,邮箱) / (微信,官网)——顺序错位会让卡片错排。
        int qq1 = page.indexOf("tencent://message/?uin=817094");
        int qq2 = page.indexOf("tencent://message/?uin=2912167928");
        int group = page.indexOf("groupcode=426669837");
        int email = page.indexOf("mailto:vfaner@88.com");
        int wechat = page.indexOf(">qqmu66<");
        assertThat(qq1).isGreaterThan(-1);
        assertThat(qq2).isGreaterThan(qq1);
        assertThat(group).isGreaterThan(qq2);
        assertThat(email).isGreaterThan(group);
        assertThat(wechat).isGreaterThan(email);
    }

    @Test
    void theWebsiteRowSharesTheGridWithWeChatInsteadOfSpanningIt() throws Exception {
        String page = render();

        // 官网是第三行的右半格（与微信同排）：wide 通栏会让它另起一行，卡片凭空多一排。
        assertThat(page).contains("https://jync.qqmu.com");
        assertThat(page).containsPattern("<div class=\"kv\">\\s*<dt>官网</dt>");
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
