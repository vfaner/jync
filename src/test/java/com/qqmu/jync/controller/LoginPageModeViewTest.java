package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:loginmode;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class LoginPageModeViewTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private OaAuthConfigService configService;

    private OaAuthConfig config;

    @BeforeEach
    void setUp() {
        config = new OaAuthConfig();
        when(configService.load()).thenReturn(config);
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url).locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void passwordModeShowsBothForms() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.PASSWORD);

        String html = page("/login");

        assertThat(html).contains("action=\"/login\"", "action=\"/login/oa\"");
    }

    @Test
    void aDisabledConfigurationRedirectsToTheLocalForm() throws Exception {
        mvc.perform(get("/login")).andExpect(redirectedUrl("/login/local"));
    }

    @Test
    void headerModeWithoutAnEntryUrlShowsThePortalPrompt() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);

        String html = page("/login");

        assertThat(html).contains("请从贵单位的统一门户进入");
        assertThat(html).contains("href=\"/login/local\"");
        assertThat(html).doesNotContain("action=\"/login/oa\"");
    }

    @Test
    void thePortalPromptPageVersionsItsAssetsToo() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);

        String html = page("/login");

        // Same reason as the local form: outside the layout, an unversioned app.js survives the
        // upgrade in the browser cache. This page is reached right after a restart, which is
        // exactly when the stale copy does its damage. theme.js ships on the same pages and is
        // just as stale-prone.
        assertThat(html).contains("href=\"/css/app.css?v=v", "src=\"/js/app.js?v=v",
                "src=\"/js/theme.js?v=v");
    }

    @Test
    void headerModeWithAnEntryUrlRedirectsThere() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);
        config.setLoginEntryUrl("https://portal.example.com/login");

        mvc.perform(get("/login")).andExpect(redirectedUrl("https://portal.example.com/login"));
    }

    @Test
    void aTicketFailureIsShownEvenWhenAnEntryUrlIsConfigured() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);
        config.setLoginEntryUrl("https://portal.example.com/login");

        String html = page("/login?ticketError");

        assertThat(html).contains("票据校验失败");
    }

    @Test
    void aTicketLockoutIsShownEvenWhenAnEntryUrlIsConfigured() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);
        config.setLoginEntryUrl("https://portal.example.com/login");

        String html = page("/login?ticketLocked");

        assertThat(html).contains("失败次数过多");
    }

    @Test
    void theLocalFormStaysAvailableInEveryMode() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.TICKET);

        String html = page("/login/local");

        assertThat(html).contains("action=\"/login\"");
        assertThat(html).doesNotContain("action=\"/login/oa\"");
    }

    @Test
    void aDisabledLocalFormRendersNoFormAtAll() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);
        config.setLocalLoginEnabled(Boolean.FALSE);

        String html = page("/login/local");

        assertThat(html).doesNotContain("action=\"/login\"");
        assertThat(html).contains("已关闭本地账号登录");
    }

    @Test
    void postingTheLocalFormWhenItIsDisabledNeverReachesTheProvider() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);
        config.setLocalLoginEnabled(Boolean.FALSE);

        mvc.perform(post("/login").with(csrf())
                        .param("username", "admin").param("password", "123456"))
                .andExpect(redirectedUrl("/login?localDisabled"));
    }

    @Test
    void aFailedLocalSignInReturnsToTheLocalFormWithTheError() throws Exception {
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);

        mvc.perform(post("/login").with(csrf())
                        .param("username", "admin").param("password", "wrong"))
                .andExpect(redirectedUrl("/login/local?error"));
    }
}
