package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The login page is what the restart watcher probes to decide the server is back. That response
 * also carries the new build's message dictionary, and {@code app.js} reads the "更新成功" text
 * from it before showing the dialog — the page the admin is looking at was rendered by the old
 * jar, which may predate the key. The login page is the only template outside the layout, so it
 * has to declare that dictionary itself.
 *
 * <p>The probe points at {@code /login/local} rather than {@code /login}: under external
 * authentication {@code /login} may redirect to the portal, while the local form always answers
 * 200 and carries the same inline dictionary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:loginupgradenotice;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class LoginPageUpgradeNoticeViewTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void loginPageHandsTheUpgradeSuccessTextToAppJs() throws Exception {
        // English keeps the assertion readable: Thymeleaf escapes CJK in an inline script as
        // Unicode escapes, so the Chinese value cannot be asserted literally here. Bundle
        // parity is covered by I18nBundleParityTest.
        String page = mvc.perform(get("/login/local").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("'msg.upgrade.success'");
        assertThat(page).contains("Upgrade successful");
    }

    @Test
    void theLoginPageServesVersionedAssetsSoAnUpgradeIsNotCachedAway() throws Exception {
        String page = mvc.perform(get("/login/local").locale(Locale.ENGLISH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // layout.html stamps app.js/app.css with ?v=<version> for a reason: an unversioned
        // app.js left in the browser cache keeps calling endpoints the new jar renamed away.
        // The login pages are outside the layout, so they have to opt in themselves — theme.js
        // included: it runs the theme/locale migration on every page load.
        assertThat(page).contains("href=\"/css/app.css?v=v", "src=\"/js/app.js?v=v",
                "src=\"/js/theme.js?v=v");
    }
}
