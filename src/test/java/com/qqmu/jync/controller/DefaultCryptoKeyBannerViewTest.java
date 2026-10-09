package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.ai.AiProviderService;

/**
 * The default crypto-key warning, rendered for real.
 *
 * <p>Connection passwords are only as secret as the key that encrypts them; with the key
 * shipped in the public jar the banner must appear on every authenticated page, and must
 * disappear once either property is overridden.
 */
@WebMvcTest(AiProviderController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class DefaultCryptoKeyBannerViewTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SyncProperties properties;

    @MockBean
    private AiProviderService service;

    private String savedPassword;
    private String savedSalt;

    @BeforeEach
    void stubPageAndRememberConfig() {
        when(service.findAll()).thenReturn(List.of());
        when(service.isAssistAvailable()).thenReturn(false);
        savedPassword = properties.getCryptoPassword();
        savedSalt = properties.getCryptoSalt();
        properties.setCryptoPassword(SyncProperties.DEFAULT_CRYPTO_PASSWORD);
        properties.setCryptoSalt(SyncProperties.DEFAULT_CRYPTO_SALT);
    }

    @AfterEach
    void restoreConfig() {
        properties.setCryptoPassword(savedPassword);
        properties.setCryptoSalt(savedSalt);
    }

    private String render(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theDefaultKeyRendersTheBanner() throws Exception {
        String html = render("/ai");

        assertThat(html).contains("id=\"default-key-banner\"");
        // No action button: the key cannot be rotated from the UI without a re-encryption path.
        assertThat(html).doesNotContain("banner-action");
        // Both bundles' copy resolves (a missing key shows as ??warn.defaultCryptoKey??).
        assertThat(html).doesNotContain("??");
    }

    @Test
    void overridingThePasswordAloneDoesNotRemoveTheBanner() throws Exception {
        // Salt is still the shipped default, so the OR keeps warning.
        properties.setCryptoPassword("a-deployer-chosen-secret");

        assertThat(render("/ai")).contains("id=\"default-key-banner\"");
    }

    @Test
    void changingOnlyOneHalfStillLeavesTheBanner() throws Exception {
        // The check is OR: password default OR salt default keeps the shipped key derivable,
        // so one half changed is not enough.
        properties.setCryptoSalt("a-deployer-chosen-salt");

        assertThat(render("/ai")).contains("id=\"default-key-banner\"");
    }

    @Test
    void overridingBothHalvesRemovesTheBanner() throws Exception {
        properties.setCryptoPassword("a-deployer-chosen-secret");
        properties.setCryptoSalt("a-deployer-chosen-salt");

        assertThat(render("/ai")).doesNotContain("id=\"default-key-banner\"");
    }

    @Test
    @WithAnonymousUser
    void noBannerBeforeSignIn() throws Exception {
        // On the login page there are no secrets shown yet; the warning is for signed-in pages.
        String html = mvc.perform(get("/ai"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("id=\"default-key-banner\"");
    }
}
