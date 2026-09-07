package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.service.ai.AiProviderService;

/**
 * Renders the AI pages for real.
 *
 * <p>A Thymeleaf typo or a message key that exists in only one bundle compiles fine and passes
 * every unit test, then throws on first view. These tests are here to make that a build failure
 * instead of a bug report.
 */
@WebMvcTest(AiProviderController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
/**
 * Signed in as ADMIN for the whole class. These slices exercise rendering and handler behaviour,
 * not authorization -- the role and CSRF rules have their own tests in
 * {@code com.synctool.config.SecurityConfigTest}. Without this the security filter chain answers
 * every request with a redirect to the login page and none of the assertions below get a chance
 * to run.
 */
@WithMockUser(roles = "ADMIN")
class AiProviderControllerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiProviderService service;

    private AiProvider provider(String name, boolean enabled, Boolean lastOk) {
        AiProvider p = new AiProvider();
        p.setId(1L);
        p.setName(name);
        p.setProtocol(AiProtocol.OPENAI);
        p.setBaseUrl("https://api.example.com/v1");
        p.setModel("some-model");
        p.setEnabled(enabled);
        p.setLastTestOk(lastOk);
        p.setLastTestMessage(lastOk == null ? null : "OK");
        p.setLastTestAt(lastOk == null ? null : Instant.now());
        return p;
    }

    @Test
    void theEmptyListRenders() throws Exception {
        when(service.findAll()).thenReturn(List.of());
        when(service.isAssistAvailable()).thenReturn(false);

        String html = mvc.perform(get("/ai"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("??");
    }

    @Test
    void aPopulatedListRendersEveryBadgeState() throws Exception {
        when(service.findAll()).thenReturn(List.of(
                provider("enabled-ok", true, true),
                provider("failed", false, false),
                provider("never-probed", false, null)));
        when(service.isAssistAvailable()).thenReturn(true);

        String html = mvc.perform(get("/ai"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Each of the three status branches has its own th:if, so all three must be exercised.
        assertThat(html)
                .contains("enabled-ok").contains("failed").contains("never-probed")
                .contains("row-active");
        // An unresolved message key renders as ??key??; that must never reach a user.
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theCreateFormRenders() throws Exception {
        String html = mvc.perform(get("/ai/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("id=\"baseUrl\"")
                .contains("id=\"model\"")
                .contains("id=\"rawApiKey\"")
                .contains("protocol-select")
                .doesNotContain("??");
    }

    @Test
    void theEditFormRendersAndNeverEchoesTheStoredKey() throws Exception {
        AiProvider existing = provider("mine", true, true);
        existing.setApiKey("enc:ciphertext-that-must-not-be-rendered");
        when(service.findById(1L)).thenReturn(Optional.of(existing));

        String html = mvc.perform(get("/ai/1/edit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The form deliberately has no field bound to apiKey; a stray th:field would ship the
        // secret to the browser in page source.
        assertThat(html)
                .doesNotContain("ciphertext-that-must-not-be-rendered")
                .doesNotContain("??");
    }

    @Test
    void aMissingProviderRedirectsRatherThanErroring() throws Exception {
        when(service.findById(any())).thenReturn(Optional.empty());

        mvc.perform(get("/ai/99/edit"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void theEnglishBundleResolvesTooNotJustTheDefault() throws Exception {
        when(service.findAll()).thenReturn(List.of(provider("p", false, null)));

        String html = mvc.perform(get("/ai").header("Accept-Language", "en-US"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Keys added to only one of the two bundles would surface here as ??key_en_US??.
        assertThat(html).doesNotContain("??");
    }
}
