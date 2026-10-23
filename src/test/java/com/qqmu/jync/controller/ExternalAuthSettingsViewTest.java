package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.service.auth.oa.TicketValidationClient;

@WebMvcTest(ExternalAuthSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class ExternalAuthSettingsViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private OaAuthConfigService configService;
    @MockBean
    private TicketValidationClient ticketClient;
    @MockBean
    private BuildProperties buildProperties;

    private OaAuthConfig stored;

    @BeforeEach
    void setUp() {
        stored = new OaAuthConfig();
        stored.setEnabled(true);
        stored.setMode(OaAuthConfig.Mode.PASSWORD);
        stored.setLoginUrl("https://oa.example.com/api/login");
        stored.setUserField("data.username");
        stored.setUserHeader("X-Auth-User");
        stored.setSecretHeaderName("X-Auth-Secret");
        stored.setSecretHeaderValue("enc:stored-ciphertext");
        when(configService.load()).thenReturn(stored);
        when(configService.save(any(OaAuthConfig.class), any(), eq(false)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(configService.save(any(OaAuthConfig.class), any(), eq(true)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void theFormExposesEveryModeAndItsFields() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("action=\"/external-auth\"", "method=\"post\"");
        for (String field : new String[] {"enabled", "mode", "adminUsers", "loginEntryUrl",
                "logoutRedirectUrl", "localLoginEnabled", "loginUrl", "userHeader", "adminHeader",
                "secretHeaderName", "secretHeaderValue", "validateUrl", "validateMethod",
                "ticketParam", "userField", "adminField", "adminValue", "timeoutMs",
                "allowInsecureHttp"}) {
            assertThat(page).as(field).contains("name=\"" + field + "\"");
        }
        assertThat(page).contains("外部认证");
        assertThat(page).doesNotContain("??");
    }

    @Test
    void theActiveSectionFollowsTheStoredMode() throws Exception {
        stored.setMode(OaAuthConfig.Mode.HEADER);

        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        // Thymeleaf keeps the line break between the static attribute and th:attr, so match
        // with a whitespace pattern instead of one literal string.
        assertThat(page).containsPattern(
                "data-mode-section=\"HEADER\"\\s+data-active=\"true\"");
        assertThat(page).containsPattern(
                "data-mode-section=\"PASSWORD\"\\s+data-active=\"false\"");
    }

    @Test
    void theStoredSecretIsNeverEchoed() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("stored-ciphertext", "enc:");
        assertThat(page).contains("已设置");
    }

    @Test
    void savingRedirectsBackAndHandsTheTypedSecretToTheService() throws Exception {
        mvc.perform(post("/external-auth").locale(Locale.SIMPLIFIED_CHINESE)
                        .param("enabled", "true")
                        .param("mode", "HEADER")
                        .param("userHeader", "X-Auth-User")
                        .param("secretHeaderName", "X-Auth-Secret")
                        .param("secretHeaderValue", "s3cr3t")
                        .param("localLoginEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/external-auth"))
                .andExpect(flash().attribute("message", "msg.oa.saved"));

        ArgumentCaptor<OaAuthConfig> saved = ArgumentCaptor.forClass(OaAuthConfig.class);
        verify(configService).save(saved.capture(), eq("s3cr3t"), eq(false));
        assertThat(saved.getValue().getMode()).isEqualTo(OaAuthConfig.Mode.HEADER);
        assertThat(saved.getValue().getUserHeader()).isEqualTo("X-Auth-User");
    }

    @Test
    void clearingTheSecretIsPassedThrough() throws Exception {
        mvc.perform(post("/external-auth").locale(Locale.SIMPLIFIED_CHINESE)
                        .param("enabled", "true")
                        .param("mode", "HEADER")
                        .param("userHeader", "X-Auth-User")
                        .param("secretHeaderName", "X-Auth-Secret")
                        .param("clearSecret", "true")
                        .param("localLoginEnabled", "true"));

        verify(configService).save(any(OaAuthConfig.class), any(), eq(true));
    }

    @Test
    void aRejectedSaveRendersTheFormAgainWithTheKeyInsteadOfRedirecting() throws Exception {
        when(configService.save(any(OaAuthConfig.class), any(), eq(false)))
                .thenThrow(new IllegalArgumentException("error.oa.userHeader.required"));

        String page = mvc.perform(post("/external-auth")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .param("enabled", "true")
                        .param("mode", "HEADER")
                        .param("localLoginEnabled", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("网关头模式下必须填写身份头名称。");
    }

    @Test
    void theHeaderProbeReportsTheTrustedPeerAndTheMappedUser() throws Exception {
        mvc.perform(post("/external-auth/test/header")
                        .locale(Locale.SIMPLIFIED_CHINESE)
                        .header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"trusted\":true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"user\":\"alice\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"role\":\"VIEWER\"")));
    }

    @Test
    void theTicketProbeNeverReturnsTheTicket() throws Exception {
        when(ticketClient.validate(any(), eq("t-1")))
                .thenReturn(OaAuthResult.success("alice", false));

        mvc.perform(post("/external-auth/test/ticket").param("ticket", "t-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"user\":\"alice\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("t-1"))));
    }
}
