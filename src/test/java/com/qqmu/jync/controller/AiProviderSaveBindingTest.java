package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AiProtocol;
import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.AiReasoningEffort;
import com.qqmu.jync.service.ai.AiProviderService;

/**
 * The provider save form binds into a DTO: {@code enabled}, the stored key and the last-probe
 * columns are not the form's to set. Enabling has its own explicitly authorized endpoint, and a
 * crafted POST must not switch a provider on or inject a key nobody entered.
 */
@WebMvcTest(AiProviderController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class AiProviderSaveBindingTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiProviderService service;

    @Test
    void theFormCannotSwitchAProviderOnOrInjectAStoredField() throws Exception {
        mvc.perform(post("/ai/save").with(csrf())
                        .param("name", "intranet")
                        .param("protocol", "OPENAI")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "some-model")
                        .param("enabled", "true")
                        .param("apiKey", "attacker-chosen-ciphertext")
                        .param("lastTestOk", "true")
                        .param("lastTestMessage", "looks fine"))
                .andExpect(status().is3xxRedirection());

        AiProvider saved = capture();
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getApiKey()).isNull();
        assertThat(saved.getLastTestOk()).isNull();
        assertThat(saved.getLastTestMessage()).isNull();
    }

    @Test
    void everyFieldTheFormShowsStillReachesTheService() throws Exception {
        mvc.perform(post("/ai/save").with(csrf())
                        .param("id", "9")
                        .param("name", "intranet")
                        .param("protocol", "ANTHROPIC")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "some-model")
                        .param("maxTokens", "8192")
                        .param("timeoutSeconds", "120")
                        .param("reasoningEffort", "HIGH"))
                .andExpect(status().is3xxRedirection());

        AiProvider saved = capture();
        assertThat(saved.getId()).isEqualTo(9L);
        assertThat(saved.getName()).isEqualTo("intranet");
        assertThat(saved.getProtocol()).isEqualTo(AiProtocol.ANTHROPIC);
        assertThat(saved.getBaseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(saved.getModel()).isEqualTo("some-model");
        assertThat(saved.getMaxTokens()).isEqualTo(8192);
        assertThat(saved.getTimeoutSeconds()).isEqualTo(120);
        assertThat(saved.getReasoningEffort()).isEqualTo(AiReasoningEffort.HIGH);
    }

    private AiProvider capture() {
        ArgumentCaptor<AiProvider> captor = ArgumentCaptor.forClass(AiProvider.class);
        verify(service).save(captor.capture(), isNull());
        return captor.getValue();
    }
}
