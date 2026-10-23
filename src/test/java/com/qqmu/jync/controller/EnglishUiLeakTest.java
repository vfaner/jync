package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.EnglishRenderingSupport;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Sweeps the English UI for Chinese text, which is the leak every other English-locale test in
 * this project cannot see.
 *
 * <p>Those tests assert {@code doesNotContain("??")} — a message key that is missing from one of
 * the two bundles. A string hardcoded in Java or in a template needs no lookup at all, so it
 * renders perfectly and arrives in the English UI untranslated, which is how "OpenAI 兼容" and
 * "自定义 Custom" shipped for several releases while every guard stayed green.
 *
 * <p>One full-context sweep rather than an assertion per view test, because the failure mode is
 * "some page was never checked": a page added tomorrow is covered here by default, whereas it
 * would have to remember to call a helper. The bundle-facing half of the same check — the strings
 * an inline i18n dictionary inside a {@code <script>} block hands to JS, which the markup check
 * cannot see — is the second test below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:englishui;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        // The system-log page reads this file. Keep it inside the build so the sweep never
        // depends on — or scans — whatever the developer's own ./logs happens to hold.
        "logging.file.name=target/englishui-logs/jync.log"
})
class EnglishUiLeakTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        return authentication(token);
    }

    /**
     * Every GET page that renders its own chrome. Login is excluded: it is reachable without a
     * session and has its own test.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/dashboard",
            "/projects", "/projects/new",
            "/databases", "/databases/new",
            "/ai", "/ai/new",
            "/settings",
            "/audits",
            "/change-logs",
            "/system-logs",
            "/oa/settings",
            "/account/password",
            // A project that does not exist exercises the not-found branch of the page shell.
            "/projects/999999", "/projects/999999/conversions",
    })
    @DisplayName("no page in the English UI renders Chinese text")
    void noEnglishPageRendersChinese(String url) throws Exception {
        String html = mvc.perform(get(url).param("lang", "en").with(asAdmin()))
                .andReturn().getResponse().getContentAsString();

        assertThat(EnglishRenderingSupport.findChinese(html))
                .as("Chinese text visible on %s at ?lang=en", url)
                .isNull();
    }

    @Test
    @DisplayName("the login page renders no Chinese in English")
    void theLoginPageRendersNoChinese() throws Exception {
        String html = mvc.perform(get("/login/local").param("lang", "en"))
                .andReturn().getResponse().getContentAsString();

        assertThat(EnglishRenderingSupport.findChinese(html)).isNull();
    }

    /**
     * The other half of the sweep: the English bundle itself.
     *
     * <p>A hardcoded Chinese string is invisible to the markup check when it lives in an inline
     * dictionary inside a {@code <script>} block, because scripts are stripped before scanning (the
     * codebase writes its JS comments in Chinese by convention). Reading the bundle from the other
     * end catches exactly those, since that dictionary is populated from these keys.
     */
    @Test
    @DisplayName("the English bundle holds no Chinese value")
    void theEnglishBundleHoldsNoChinese() throws Exception {
        String body = new String(new ClassPathResource("i18n/messages.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(EnglishRenderingSupport.findChineseValue(body))
                .as("Chinese value in i18n/messages.properties")
                .isNull();
    }

    /**
     * The upgrade status text leaves the server already worded — app.js renders it verbatim, with
     * no dictionary lookup — so it is the one JSON body that can leak Chinese the same way a page
     * can, and it is checked here alongside them.
     */
    @Test
    @DisplayName("the upgrade status JSON holds no Chinese message")
    void theUpgradeStatusIsEnglish() throws Exception {
        String json = mvc.perform(get("/api/upgrade/status").param("lang", "en").with(asAdmin()))
                .andReturn().getResponse().getContentAsString();

        // Idle carries no message at all; what matters is that nothing Chinese can come back.
        assertThat(EnglishRenderingSupport.findChinese(json)).isNull();
    }

    /**
     * A sanity check on the sweep itself: if {@code findChinese} were broken the parameterized
     * test above would pass for every page and prove nothing. This pins that it does detect
     * Chinese, using text every page in this app is full of in its own language.
     */
    @Test
    @DisplayName("the sweep would notice Chinese if there were any")
    void theSweepCanFail() {
        assertThat(EnglishRenderingSupport.findChinese("<p>设置</p>")).isNotNull();
        assertThat(EnglishRenderingSupport.findChinese(List.of("English only").toString())).isNull();
    }

    /**
     * The one Chinese region the sweep deliberately skips: {@code <div class="release-notes">} on
     * /settings. It is not a UI string this project translates — it is publisher-authored prose
     * bundled from {@code docs/release-notes/} (falling back to the release body from the forge),
     * written for a Chinese-first audience and rendered verbatim through {@code th:utext}. Every
     * other page in the app renders only message keys, so the whole class of bug this sweep hunts
     * for is out of this block's reach; translating it is a documentation decision, not a code one.
     *
     * <p>The exclusion is one container, not one page or one URL: the rest of /settings — its
     * densest card, its upgrade dialog — stays under the sweep. That is the difference between a
     * documented carve-out and an exemption list, which would hide the next real leak that lands
     * here.
     */
    @Test
    @DisplayName("the sweep skips the release-notes block and nothing else")
    void theSweepSkipsOnlyTheReleaseNotesBlock() {
        assertThat(EnglishRenderingSupport.findChinese(
                "<div class=\"release-notes\"><h1>Jync v1.7.5</h1><p>本版新增</p></div>"))
                .isNull();
        // Nested markup inside the block must not end the skip early.
        assertThat(EnglishRenderingSupport.findChinese(
                "<div class=\"release-notes\"><div><p>本版新增</p></div><p>还有</p></div>"))
                .isNull();
        // The same Chinese outside the block is still a leak, before it...
        assertThat(EnglishRenderingSupport.findChinese(
                "<p>本版新增</p><div class=\"release-notes\"><p>说明</p></div>"))
                .isNotNull();
        // ...and after it.
        assertThat(EnglishRenderingSupport.findChinese(
                "<div class=\"release-notes\"><p>说明</p></div><p>设置</p>"))
                .isNotNull();
    }
}
