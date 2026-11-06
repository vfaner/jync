package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.settings.SystemSettingService;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The administrator's 系统配置 page: metadata database, the HTTPS switch, and the configuration
 * package.
 *
 * <p>It never offers a field for {@code app.security.trusted-proxies}: that list decides which
 * peers may assert HTTPS and which forwarding headers are believed, so editing it from a web
 * form would let a compromised admin account defeat both. It stays in the configuration file.
 */
@WebMvcTest(SystemConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.update-check.enabled=false"
})
class SystemConfigViewTest {

    private static final RequireHttpsResolver.Effective HTTPS_OFF =
            new RequireHttpsResolver.Effective(false, RequireHttpsResolver.Source.DEFAULT);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RequireHttpsResolver requireHttpsResolver;
    @MockBean
    private SystemSettingService systemSettingService;
    @MockBean
    private AdminAuditService adminAuditService;
    @MockBean
    private CryptoUtil cryptoUtil;

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

    private String render(UserRole role) throws Exception {
        when(requireHttpsResolver.effective()).thenReturn(HTTPS_OFF);
        when(requireHttpsResolver.isConfigControlled()).thenReturn(false);
        return mvc.perform(get("/system-config").locale(Locale.SIMPLIFIED_CHINESE).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void thePageCarriesTheThreeConfigurationCards() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("元数据库", "强制 HTTPS", "配置包");
    }

    @Test
    void theSystemGroupIsTheActiveOneHere() throws Exception {
        assertThat(render(UserRole.ADMIN)).contains("nav-system is-active");
    }

    @Test
    void trustedProxiesAreNotEditableFromThePage() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).doesNotContain("name=\"trustedProxies\"")
                .doesNotContain("name=\"trusted-proxies\"");
    }

    @Test
    void theMetadataTestFormNeverCarriesAPasswordBack() throws Exception {
        String page = render(UserRole.ADMIN);

        int field = page.indexOf("id=\"db-test-password\"");
        assertThat(field).as("the metadata test form has a password field").isGreaterThan(-1);
        String tag = page.substring(field, page.indexOf('>', field));
        // Nothing was typed, so there is nothing to echo — and after a failed test the browser
        // must not have last request's password sitting in the field.
        assertThat(tag).doesNotContain("value=");
    }

    @Test
    void passphraseFieldsAskTheBrowserNotToRememberThem() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).containsPattern("type=\"password\"[^>]*autocomplete=\"new-password\"");
        // Export, its confirmation, and import each get a field.
        assertThat(page.split("autocomplete=\"new-password\"", -1)).hasSizeGreaterThanOrEqualTo(4);
    }
}
