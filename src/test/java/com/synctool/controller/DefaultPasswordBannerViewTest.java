package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.service.ai.AiProviderService;
import com.synctool.service.auth.SyncUserDetails;

/**
 * The "you are still on the default password" banner, rendered for real.
 *
 * <p>Any page would do — the banner lives in the shared layout — so this rides on {@code /ai}
 * purely because that controller has a single collaborator to mock.
 *
 * <p>Worth its own class because the banner is the one part of the layout the other view tests
 * cannot reach: they authenticate with {@code @WithMockUser}, whose principal is not a
 * {@link SyncUserDetails}, so {@code usingDefaultPassword} is always false there and the whole
 * block is skipped.
 */
@WebMvcTest(AiProviderController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DefaultPasswordBannerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiProviderService service;

    @BeforeEach
    void stubTheListPage() {
        when(service.findAll()).thenReturn(List.of());
        when(service.isAssistAvailable()).thenReturn(false);
    }

    /**
     * A principal carrying the default-password flag.
     *
     * <p>{@code @WithMockUser} cannot express this: the flag lives on {@link SyncUserDetails}, and
     * the advice reads it off the principal rather than from an authority.
     */
    private static RequestPostProcessor as(String username, boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render(RequestPostProcessor who, String acceptLanguage) throws Exception {
        return mvc.perform(get("/ai").header("Accept-Language", acceptLanguage).with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theBannerNamesTheSignedInAccountAndNoOther() throws Exception {
        String html = render(as("admin", true), "zh-CN");

        assertThat(html).contains("default-pw-banner");
        // The point of the parameter: the warning is about *this* account, so the name has to be
        // in the sentence. A broken message parameter would silently render a literal "{0}".
        assertThat(html).contains("admin").doesNotContain("{0}");
        // The old copy claimed both seeded accounts were still on the default, which is a guess
        // dressed up as a fact -- the flag only knows about the account that just signed in.
        assertThat(html).doesNotContain("两个账号");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theEnglishBundleResolvesTheParameterToo() throws Exception {
        String html = render(as("viewer-account", true), "en-US");

        assertThat(html).contains("default-pw-banner");
        assertThat(html).contains("viewer-account").doesNotContain("{0}");
        assertThat(html).doesNotContain("Both accounts");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theBannerCarriesADismissControl() throws Exception {
        String html = render(as("admin", true), "zh-CN");

        // The close button and the pre-paint check are what make the banner dismissable for one
        // session; losing either turns "close" into a no-op or into a visible flash on every page.
        assertThat(html)
                .contains("data-role=\"dismiss-default-pw\"")
                .contains("synctool-pw-banner-dismissed");
    }

    @Test
    void aChangedPasswordRemovesTheBannerEntirely() throws Exception {
        String html = render(as("admin", false), "zh-CN");

        // Not merely hidden: the dismiss state is per-session, so if the banner were still in the
        // markup a user who had already changed their password could be nagged again.
        assertThat(html)
                .doesNotContain("default-pw-banner")
                .doesNotContain("data-role=\"dismiss-default-pw\"");
    }
}
