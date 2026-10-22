package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;

/**
 * The forced-change gate: while the signed-in account still uses the seeded password, every
 * request except the change form itself (and the assets that render it) is turned away.
 */
class ForcePasswordChangeFilterTest {

    private final ForcePasswordChangeFilter filter = new ForcePasswordChangeFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aDefaultPasswordUserIsSentToThePasswordPageFromAnyOtherPage() throws Exception {
        signIn(true);

        MockHttpServletRequest request = get("/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/account/password");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void thePasswordFormItselfIsAllowedThrough() throws Exception {
        signIn(true);

        MockHttpServletRequest request = get("/account/password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void theAssetsTheFormNeedsAreAllowedThrough() throws Exception {
        signIn(true);

        for (String path : new String[] {
                "/css/app.css", "/js/app.js", "/vendor/x.js", "/assets/logo.svg",
                "/favicon.ico"}) {
            MockHttpServletRequest request = get(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getRedirectedUrl()).as(path).isNull();
            assertThat(chain.getRequest()).as(path).isSameAs(request);
        }
    }

    /** Signing out has to work, or the only way off the page is closing the browser. */
    @Test
    void signingOutIsAllowedThrough() throws Exception {
        signIn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anApiCallIsRefusedInJson() throws Exception {
        signIn(true);

        MockHttpServletRequest request = get("/api/projects/1/preview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("account.error.mustChangePassword");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aUserWhoChangedThePasswordIsUnaffected() throws Exception {
        signIn(false);

        MockHttpServletRequest request = get("/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anUnauthenticatedRequestIsUnaffected() throws Exception {
        MockHttpServletRequest request = get("/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static void signIn(boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
