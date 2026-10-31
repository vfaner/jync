package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.AlertConfigForm;
import com.qqmu.jync.model.AlertConfig;
import com.qqmu.jync.model.AlertWebhookFormat;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.alert.AlertConfigService;
import com.qqmu.jync.service.alert.AlertNotifier;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The alerting settings page: every category is offered, the stored webhook secret never reaches
 * the page, and a rejected save comes back with an explanation instead of a 500.
 */
@WebMvcTest(AlertSettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class AlertSettingsViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private AlertConfigService configService;
    @MockBean private AlertNotifier notifier;

    private AlertConfig config(boolean enabled, String storedUrl) {
        AlertConfig config = new AlertConfig();
        config.setId(1L);
        config.setEnabled(enabled);
        config.setWebhookUrl(storedUrl);
        config.setFormat(AlertWebhookFormat.GENERIC);
        config.setCooldownMinutes(30);
        config.setCategories("SYNC_FAILURE,POLL_STUCK");
        return config;
    }

    private String render() throws Exception {
        when(configService.load()).thenReturn(config(true, "enc:super-secret-token-1234"));
        when(configService.maskedUrl()).thenReturn("••••1234");
        return mvc.perform(get("/alert-settings").header("Accept-Language", "zh-CN")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void thePageOffersAllSevenCategories() throws Exception {
        String page = render();

        assertThat(page).contains("同步失败", "轮询卡死", "项目未运行", "CDC 停摆",
                "数据漂移", "连接健康", "PG slot 积压");
        assertThat(page).contains("发送测试消息");
    }

    /**
     * A desktop window is wide enough for both cards; stacking them in one 860px column left
     * half the screen empty. The two cards share one grid, and the single POST form still
     * wraps both of them plus the action row — the buttons may leave the category card, but
     * they must not leave the form.
     */
    @Test
    void theCardsSitSideBySideInOneForm() throws Exception {
        String page = render();

        assertThat(page).contains("class=\"split split-cols\"");
        int form = page.indexOf("id=\"alert-settings-form\"");
        int split = page.indexOf("class=\"split split-cols\"");
        int delivery = page.indexOf("推送设置");
        int categories = page.indexOf("告警分类");
        int save = page.indexOf(">保存</span>");
        assertThat(form).isGreaterThanOrEqualTo(0);
        assertThat(split).isGreaterThan(form);
        assertThat(delivery).isGreaterThan(split);
        assertThat(categories).isGreaterThan(delivery);
        assertThat(save).isGreaterThan(categories);
    }

    @Test
    void onlyTheSavedCategoriesArePrefilled() throws Exception {
        String page = render();

        assertThat(page).contains(
                "name=\"categories\" value=\"SYNC_FAILURE\" checked=\"checked\"");
        assertThat(page).contains(
                "name=\"categories\" value=\"POLL_STUCK\" checked=\"checked\"");
        assertThat(page).doesNotContain(
                "name=\"categories\" value=\"CDC_STOPPED\" checked=\"checked\"");
    }

    @Test
    void theStoredWebhookSecretIsNeverRendered() throws Exception {
        String page = render();

        assertThat(page).doesNotContain("super-secret-token");
        assertThat(page).contains("••••1234");
    }

    @Test
    void savingPostsTheSettingsAndRedirects() throws Exception {
        mvc.perform(post("/alert-settings").with(asAdmin()).with(csrf())
                        .param("enabled", "true")
                        .param("webhookUrl", "https://hooks.example.com/x")
                        .param("format", "GENERIC")
                        .param("categories", "SYNC_FAILURE")
                        .param("cooldownMinutes", "30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/alert-settings"))
                .andExpect(flash().attribute("message", "alert.saved"));

        org.mockito.ArgumentCaptor<AlertConfigForm> saved =
                org.mockito.ArgumentCaptor.forClass(AlertConfigForm.class);
        verify(configService).save(saved.capture());
        // Binding from the page's repeated checkbox parameter, not just "the service was called":
        // a mis-bound list would silently save zero categories and refuse the save.
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getCategories()).containsExactly("SYNC_FAILURE");
        assertThat(saved.getValue().getCooldownMinutes()).isEqualTo(30);
    }

    @Test
    void aRejectedSaveComesBackWithAnExplanation() throws Exception {
        when(configService.load()).thenReturn(config(true, null));
        when(configService.maskedUrl()).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("error.alert.url.required"))
                .when(configService).save(any(AlertConfigForm.class));

        String page = mvc.perform(post("/alert-settings").with(asAdmin()).with(csrf())
                        .header("Accept-Language", "zh-CN")
                        .param("enabled", "true")
                        .param("categories", "SYNC_FAILURE")
                        .param("cooldownMinutes", "30"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("启用告警时必须填写 Webhook 地址");
    }

    @Test
    void aFailedTestSendIsReported() throws Exception {
        when(notifier.sendNow(anyString(), any(), anyString())).thenReturn(false);

        mvc.perform(post("/alert-settings/test").with(asAdmin()).with(csrf())
                        .param("webhookUrl", "https://hooks.example.com/x")
                        .param("format", "GENERIC"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/alert-settings"))
                .andExpect(flash().attribute("message", "alert.testFailed"));

        verify(notifier).sendNow(eq("https://hooks.example.com/x"),
                eq(AlertWebhookFormat.GENERIC), anyString());
    }

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
