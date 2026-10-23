package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/**
 * The administrator's OA sign-in settings page. Authorization is covered by
 * {@code SecurityConfigTest}; this class pins the form's fields and the two POST outcomes.
 */
@WebMvcTest(OaAuthSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class OaSettingsViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OaAuthConfigService configService;
    @MockBean
    private BuildProperties buildProperties;

    private OaAuthConfig stored;

    @BeforeEach
    void setUp() {
        stored = new OaAuthConfig();
        stored.setEnabled(true);
        stored.setLoginUrl("https://oa.example.com/api/login");
        stored.setRequestFormat(OaAuthConfig.RequestFormat.JSON);
        stored.setUsernameField("user");
        stored.setPasswordField("pass");
        stored.setSuccessField("code");
        stored.setSuccessValue("0");
        stored.setUserField("data.username");
        stored.setAdminField("data.isAdmin");
        stored.setAdminValue("true,1");
        stored.setTimeoutMs(8000);
        stored.setAllowInsecureHttp(true);
        when(configService.load()).thenReturn(stored);
        when(configService.save(any(OaAuthConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void theFormCarriesEverySetting() throws Exception {
        String page = mvc.perform(get("/oa/settings").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("action=\"/oa/settings\"", "method=\"post\"");
        for (String field : new String[] {"enabled", "loginUrl", "requestFormat", "usernameField",
                "passwordField", "successField", "successValue", "userField", "adminField",
                "adminValue", "timeoutMs", "allowInsecureHttp"}) {
            assertThat(page).as(field).contains("name=\"" + field + "\"");
        }
        assertThat(page).contains("value=\"https://oa.example.com/api/login\"");
        assertThat(page).contains("OA 配置");
        // No unresolved message keys leaked into the page.
        assertThat(page).doesNotContain("??");
    }

    @Test
    void savingRedirectsBackWithAMessage() throws Exception {
        mvc.perform(post("/oa/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .param("enabled", "true")
                        .param("loginUrl", "https://oa.example.com/login")
                        .param("requestFormat", "FORM")
                        .param("usernameField", "user")
                        .param("passwordField", "pass")
                        .param("userField", "data.username")
                        .param("timeoutMs", "9000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oa/settings"))
                .andExpect(flash().attribute("message", "msg.oa.saved"));

        ArgumentCaptor<OaAuthConfig> saved = ArgumentCaptor.forClass(OaAuthConfig.class);
        verify(configService).save(saved.capture());
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getLoginUrl()).isEqualTo("https://oa.example.com/login");
        assertThat(saved.getValue().getRequestFormat())
                .isEqualTo(OaAuthConfig.RequestFormat.FORM);
        assertThat(saved.getValue().getTimeoutMs()).isEqualTo(9000);
    }

    @Test
    void aRejectedSettingComesBackAsAnErrorMessage() throws Exception {
        when(configService.save(any(OaAuthConfig.class)))
                .thenThrow(new IllegalArgumentException("error.oa.url.required"));

        mvc.perform(post("/oa/settings").locale(Locale.SIMPLIFIED_CHINESE)
                        .param("enabled", "true")
                        .param("loginUrl", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oa/settings"))
                .andExpect(flash().attribute("error", "error.oa.url.required"));
    }
}
