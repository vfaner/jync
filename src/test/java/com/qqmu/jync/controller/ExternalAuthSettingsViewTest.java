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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
    private OaAuthConfigService configService;
    @MockitoBean
    private TicketValidationClient ticketClient;
    @MockitoBean
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
    void turningLocalSignInOffExplainsTheRescueSwitch() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        // The one way back in has to be on the page where the lockout can be created.
        assertThat(page).contains("JYN_RESET_LOGIN");
        assertThat(page).contains("关闭本地登录后");
    }

    @Test
    void thePortalEntryIsOnlyOfferedForThePortalModes() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        int common = page.indexOf("data-mode-section=\"COMMON\"");
        int password = page.indexOf("data-mode-section=\"PASSWORD\"");
        assertThat(common).isGreaterThan(-1);
        assertThat(password).isGreaterThan(common);

        // 门户入口只在 HEADER/TICKET 模式下显示，由卡片上的 data-mode-any 决定；两栏布局后
        // 它不再与登录 URL 相邻，所以不能再靠文档位置判断，检查卡片自己的作用域。
        int portal = page.indexOf("class=\"card oa-card-portal\"");
        assertThat(portal).isGreaterThan(-1);
        // 带尾随空格，避开卡内的 <div class="card-head">。
        int nextCard = page.indexOf("<div class=\"card ", portal);
        String portalCard = page.substring(portal, nextCard < 0 ? page.length() : nextCard);
        assertThat(portalCard).contains("loginEntryUrl", "门户登录入口",
                "data-mode-any=\"HEADER,TICKET\"");
    }

    @Test
    void theDesktopLayoutPutsTheModeAndMappingCardsInTheirOwnColumn() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        int split = page.indexOf("class=\"split oa-cols\"");
        int common = page.indexOf("data-mode-section=\"COMMON\"");
        int portal = page.indexOf("data-mode-any=\"HEADER,TICKET\"");
        int password = page.indexOf("data-mode-section=\"PASSWORD\"");
        // PASSWORD,TICKET 这个标记在通用设置卡里也出现过，用映射卡自己的字段定位。
        int mapping = page.indexOf("id=\"successField\"");

        // 两栏并排：通用设置（连同门户入口）在左，模式卡与响应映射在右。原先单栏堆叠把页面
        // 拉得很长，屏幕右侧却一直空着；窄屏由 .split 的断点收回单列。
        assertThat(split).as("the form uses the two-column settings layout").isNotNegative();
        assertThat(portal).isGreaterThan(common);
        assertThat(password).as("the right column starts after the left one").isGreaterThan(portal);
        assertThat(mapping).isGreaterThan(password);
    }

    @Test
    void theSaveBarAndConnectivityTestSitUnderTheLeftColumn() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        int common = page.indexOf("data-mode-section=\"COMMON\"");
        int save = page.indexOf("保存设置");
        int test = page.indexOf("id=\"external-auth-test-result\"");
        int password = page.indexOf("data-mode-section=\"PASSWORD\"");

        // 保存与连通性测试并入左栏，紧接通用设置向下排，不再各占一整行；
        // 右栏只留模式卡与响应映射。
        assertThat(save).as("the save button").isGreaterThan(common);
        assertThat(test).as("the connectivity test card").isGreaterThan(save);
        assertThat(test).as("both stay in the left column").isLessThan(password);
    }

    @Test
    void theTimeoutAndPlainHttpSwitchFollowTheMode() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        // Gateway-header mode makes no outbound request and never judges an http endpoint, so
        // both controls are meaningless there.
        assertThat(page).containsPattern(
                "class=\"field\" data-mode-any=\"PASSWORD,TICKET\"[\\s\\S]{0,300}id=\"timeoutMs\"");
        assertThat(page).containsPattern(
                "class=\"field\" data-mode-any=\"PASSWORD,TICKET\"[\\s\\S]{0,300}"
                        + "id=\"allowInsecureHttp\"");
    }

    @Test
    void theHeadlineNamesTheModeThatIsLive() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("当前生效：账号密码（OA 接口校验）");
    }

    @Test
    void theHeadlineSaysWhenExternalAuthenticationIsOff() throws Exception {
        stored.setEnabled(false);

        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("外部认证未启用");
    }

    @Test
    void theStoredSecretIsNeverEchoed() throws Exception {
        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("stored-ciphertext", "enc:");
        assertThat(page).contains("已设置");
    }

    @Test
    void anEmptySecretFieldDoesNotClaimASecretIsStored() throws Exception {
        stored.setSecretHeaderValue(null);

        String page = mvc.perform(get("/external-auth").locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // "Already set; leave blank to keep it" is only true when something is stored. Rendered
        // unconditionally it tells a first-time setup that the secret already exists.
        assertThat(page).doesNotContain("已设置；留空表示不修改。");
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
