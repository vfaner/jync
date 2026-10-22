package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.AppUserService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The change-password page has to say why it is the only page reachable, and the voluntary visit
 * must keep its "cancel" way out.
 */
@WebMvcTest(AuthController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ForcedPasswordChangeViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AppUserService userService;

    private String render(boolean usingDefaultPassword, String acceptLanguage) throws Exception {
        return mvc.perform(get("/account/password")
                        .header("Accept-Language", acceptLanguage)
                        .with(as(usingDefaultPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theForcedVisitExplainsWhyOtherPagesAreUnavailable() throws Exception {
        String html = render(true, "zh-CN");

        assertThat(html).contains("请先修改密码");
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theForcedVisitHasNoCancelLinkBackToAPageThatWouldBounce() throws Exception {
        assertThat(render(true, "zh-CN")).doesNotContain("id=\"password-cancel\"");
    }

    @Test
    void aVoluntaryVisitStillOffersCancel() throws Exception {
        assertThat(render(false, "zh-CN")).contains("id=\"password-cancel\"");
    }

    private static RequestPostProcessor as(boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
