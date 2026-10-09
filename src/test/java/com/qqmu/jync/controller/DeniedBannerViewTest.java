package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.ai.AiProviderService;

/**
 * The {@code /?denied} redirect must come back with a visible explanation.
 *
 * <p>{@code AccessDeniedHandler} redirects a refused form POST to {@code /?denied}; without the
 * layout banner that is a silent no-op, leaving the read-only user unsure whether the click did
 * anything.
 */
@WebMvcTest(AiProviderController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class DeniedBannerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiProviderService service;

    @BeforeEach
    void stubTheListPage() {
        when(service.findAll()).thenReturn(List.of());
        when(service.isAssistAvailable()).thenReturn(false);
    }

    @Test
    void deniedParameterRendersTheForbiddenBanner() throws Exception {
        String html = mvc.perform(get("/ai?denied"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The marker id distinguishes the banner from the same copy embedded in the layout's
        // inline JS message table.
        assertThat(html).contains("id=\"denied-alert\"");
        // A missing key would surface as "??error.forbidden??".
        assertThat(html).doesNotContain("??");
    }

    /**
     * Regression: a flash error WITHOUT a colon used to abort the whole render. Thymeleaf's
     * {@code #strings.substringBefore} returns null when the separator is absent, and the old
     * layout passed that null straight into {@code msgOrNull}, which throws.
     */
    @Test
    void flashErrorWithoutColonStillRendersThePage() throws Exception {
        String html = mvc.perform(get("/ai").flashAttr("error", "error.connection.not.found"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Connection not found");
        assertThat(html).doesNotContain("??");
    }

    /** The "i18n key:context" form keeps resolving the key and appending the raw context. */
    @Test
    void flashErrorWithColonAppendsTheContext() throws Exception {
        String html = mvc.perform(get("/ai")
                        .flashAttr("error", "error.connection.role.source.in.use:p1, p2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("p1, p2");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void noDeniedParameterMeansNoBanner() throws Exception {
        String html = mvc.perform(get("/ai"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("id=\"denied-alert\"");
    }
}
