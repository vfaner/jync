package com.synctool.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.service.auth.SyncUserDetails;

/**
 * The authorization rules, against the real filter chain.
 *
 * <p>Deliberately asserts on the security outcome only — "was this allowed through" — and not on
 * what the handler did afterwards. A write endpoint invoked with no parameters will validate,
 * redirect, or blow up depending on the endpoint, and none of that is what these rules are for;
 * pinning it here would make the suite fail whenever unrelated validation changed.
 *
 * <p>Runs on an in-memory database so the suite does not create {@code ./data} in the working
 * directory, and with AI disabled so nothing reaches for a network client. The demo user names
 * match the seeded accounts so that the password-change endpoint can find the user in the
 * database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:sectest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class SecurityConfigTest {

    /** Representative of the whole class of writes: every mutation in the app is a POST. */
    private static final String A_WRITE = "/databases/save";

    private static final String AN_API_WRITE = "/api/databases/test";

    /**
     * The only POST a viewer is meant to reach: changing their own password.
     *
     * <p>Safe to exempt because the handler takes the account from the authenticated principal and
     * not from a parameter, so it cannot be aimed at another user.
     */
    private static final Set<String> VIEWER_MAY_POST = Set.of("/account/password");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private int statusOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /**
     * A principal the filter chain treats as a real authenticated user.
     *
     * <p>Built from a detached {@link AppUser} rather than with {@code user("view").roles(...)},
     * because {@code AuthController} reads the principal as a {@link SyncUserDetails} and a plain
     * mock user would arrive as {@code null} there.
     */
    private static RequestPostProcessor as(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private static RequestPostProcessor asViewer() {
        return as("view", UserRole.VIEWER);
    }

    private static RequestPostProcessor asAdmin() {
        return as("admin", UserRole.ADMIN);
    }

    // ── Nobody signed in ───────────────────────────────────────────────────────

    @Test
    void aPageRedirectsToTheLoginForm() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * An expired session must not answer {@code fetch()} with a login page.
     *
     * <p>{@code postJson()} degrades anything it cannot parse as JSON to the literal string
     * {@code "HTTP <status>"}, so an HTML redirect here would reach the user as a bare number.
     */
    @Test
    void anApiCallGetsJsonNotARedirect() throws Exception {
        mvc.perform(get("/api/databases/discover-drivers"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .contains("application/json"));
    }

    @Test
    void theLoginPageAndItsAssetsAreReachable() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/js/app.js")).andExpect(status().isOk());
    }

    // ── VIEWER ─────────────────────────────────────────────────────────────────

    @Test
    void aViewerCanReadEveryListingPage() throws Exception {
        for (String page : new String[] {"/", "/projects", "/databases", "/ai", "/change-logs"}) {
            assertThat(statusOf(get(page).with(asViewer()))).as(page).isEqualTo(200);
        }
    }

    /**
     * An HTML write is refused with a redirect to {@code /?denied} (302) rather than a bare 403,
     * because the {@link org.springframework.security.web.access.AccessDeniedHandler} sends the
     * browser to a page that can render a message.
     */
    @Test
    void aViewerCannotWrite() throws Exception {
        assertThat(statusOf(post(A_WRITE).with(asViewer()).with(csrf())))
                .isEqualTo(302);
    }

    /** A rejected write on a JSON endpoint has to come back as JSON. */
    @Test
    void aViewersApiWriteIsRefusedInJson() throws Exception {
        mvc.perform(post(AN_API_WRITE).with(asViewer()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .contains("application/json"));
    }

    /**
     * Form pages write nothing, but a read-only user has no use for a form whose Save button is
     * guaranteed to fail, and driver discovery would disclose server-side paths.
     */
    @Test
    void aViewerIsNotOfferedTheFormPages() throws Exception {
        // HTML pages redirect to /?denied; API endpoints return 403.
        for (String page : new String[] {"/databases/new", "/projects/new", "/ai/new"}) {
            assertThat(statusOf(get(page).with(asViewer()))).as(page).isEqualTo(302);
        }
        assertThat(statusOf(get("/api/databases/discover-drivers").with(asViewer())))
                .isEqualTo(403);
    }

    /**
     * The one POST a viewer must be allowed, and the reason its rule has to sit above the blanket
     * {@code POST /** → ADMIN} rule: otherwise the change-password page would be shown to someone
     * forbidden to submit it.
     */
    @Test
    void aViewerMayChangeTheirOwnPassword() throws Exception {
        assertThat(statusOf(post("/account/password")
                .with(asViewer())
                .with(csrf())
                .param("currentPassword", "123456")
                .param("newPassword", "abc123")
                .param("confirmPassword", "abc123")))
                .isNotEqualTo(403);
    }

    @Test
    void aViewerCanOpenTheChangePasswordPage() throws Exception {
        assertThat(statusOf(get("/account/password").with(asViewer())))
                .isEqualTo(200);
    }

    // ── 前提本身 ───────────────────────────────────────────────────────────────

    /**
     * Every write in the application, not a representative one.
     *
     * <p>{@link #aViewerCannotWrite()} above proves the rule fires; this proves it covers the
     * whole surface. The endpoint list is read out of the dispatcher rather than typed here, so
     * adding a controller method adds a case automatically — the failure mode being guarded
     * against is a new write endpoint that nobody remembers to authorize.
     *
     * <p>The assertion is on the redirect <em>target</em>, not merely on 302. A write that slipped
     * through would also answer 302 — its handler's own redirect — so a status-only check would
     * pass in exactly the situation this test exists to catch.
     */
    @Test
    void everyWriteEndpointInTheAppRefusesAViewer() throws Exception {
        Set<String> writes = mappedPaths(RequestMethod.POST);

        // Guards the enumeration itself: a getHandlerMethods() that came back empty would make
        // every assertion below vacuous and the test green for the wrong reason.
        assertThat(writes).as("POST endpoints discovered").hasSizeGreaterThan(15);

        for (String pattern : writes) {
            if (VIEWER_MAY_POST.contains(pattern)) {
                continue;
            }
            String path = concrete(pattern);
            if (pattern.startsWith("/api/")) {
                assertThat(statusOf(post(path).with(asViewer()).with(csrf())))
                        .as(pattern).isEqualTo(403);
            } else {
                mvc.perform(post(path).with(asViewer()).with(csrf()))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/?denied"));
            }
        }
    }

    /**
     * The single-rule design has one load-bearing assumption: a write is a POST.
     *
     * <p>{@code POST /**} authorizes by verb, so a handler mapped to PUT, PATCH or DELETE — or to
     * no verb at all, which accepts every verb — is not covered by it and falls through to
     * {@code anyRequest().authenticated()}, where a viewer is a perfectly acceptable caller. That
     * is a silent hole: it needs no mistake in this config file, only a new annotation elsewhere.
     *
     * <p>Restricted to this application's own handlers; Spring's error controller is mapped
     * without a verb by design.
     */
    @Test
    void noHandlerUsesAVerbTheRulesDoNotCover() {
        List<String> offenders = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getName().startsWith("com.synctool")) {
                return;
            }
            Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
            String where = handler.getBeanType().getSimpleName() + "#"
                    + handler.getMethod().getName() + " " + patternsOf(info);
            if (verbs.isEmpty()) {
                offenders.add(where + " accepts every verb (no method on its mapping)");
                return;
            }
            verbs.stream()
                    .filter(verb -> verb != RequestMethod.GET && verb != RequestMethod.POST)
                    .forEach(verb -> offenders.add(where + " is mapped to " + verb));
        });

        assertThat(offenders)
                .as("handlers outside the GET/POST rules in SecurityConfig")
                .isEmpty();
    }

    /** Every path pattern mapped to {@code verb}, as the dispatcher itself sees them. */
    private Set<String> mappedPaths(RequestMethod verb) {
        Set<String> paths = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getName().startsWith("com.synctool")
                    && info.getMethodsCondition().getMethods().contains(verb)) {
                paths.addAll(patternsOf(info));
            }
        });
        return paths;
    }

    /** Path patterns, from whichever of the two matching strategies is in use. */
    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return info.getPatternsCondition().getPatterns();
    }

    /**
     * Turns {@code /projects/{id}/cursor} into a requestable path.
     *
     * <p>The substituted values need not be valid: authorization is decided before the handler
     * runs, so a denied request never looks at them — and a request that is *not* denied fails
     * this test regardless of what the handler then makes of "1".
     */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^{}]*\\}", "1");
    }

    // ── ADMIN ──────────────────────────────────────────────────────────────────

    @Test
    void anAdminReachesTheFormPages() throws Exception {
        for (String page : new String[] {"/databases/new", "/projects/new", "/ai/new"}) {
            assertThat(statusOf(get(page).with(asAdmin()))).as(page).isEqualTo(200);
        }
    }

    @Test
    void anAdminsWriteIsNotBlocked() throws Exception {
        assertThat(statusOf(post(A_WRITE).with(asAdmin()).with(csrf())))
                .isNotEqualTo(403);
    }

    // ── CSRF ───────────────────────────────────────────────────────────────────

    /**
     * The reason the Spring Security starter was worth taking on rather than a hand-written
     * filter. With no login there was nothing to forge; once an ADMIN session exists, a
     * third-party page can make that browser POST here.
     */
    @Test
    void aWriteWithoutTheTokenIsRefusedEvenForAnAdmin() throws Exception {
        // HTML endpoint redirects to /?denied; API endpoint returns 403.
        assertThat(statusOf(post(A_WRITE).with(asAdmin()))).isEqualTo(302);
        assertThat(statusOf(post(AN_API_WRITE).with(asAdmin()))).isEqualTo(403);
    }
}