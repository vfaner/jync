package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;

class ExternalSessionSignInTest {

    private AdminAuditService auditService;
    private ExternalSessionSignIn signIn;

    @BeforeEach
    void setUp() {
        auditService = mock(AdminAuditService.class);
        signIn = new ExternalSessionSignIn(auditService);
    }

    @Test
    void signingInReplacesTheSessionIdAndPersistsTheExternalPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("127.0.0.1");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        String before = session.getId();

        signIn.signIn(request, new MockHttpServletResponse(), "alice", UserRole.VIEWER, "HEADER");

        assertThat(session.getId()).isNotEqualTo(before);
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo("alice");
        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.isExternal()).isTrue();
        assertThat(principal.isUsingDefaultPassword()).isFalse();
        assertThat(principal.getRole()).isEqualTo(UserRole.VIEWER);
        verify(auditService).record(eq(AuditAction.LOGIN), contains("@method:header"),
                eq("alice"));
    }

    /**
     * The pre-sign-in page already handed the browser a CSRF token. Keeping it after the
     * privilege level changes would let a token minted for an anonymous session drive the new
     * authenticated one (the classic session-fixation/CSRF-token-reuse pairing).
     */
    @Test
    void signingInRotatesTheCsrfToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        CsrfToken before = repository.generateToken(request);
        repository.saveToken(before, request, new MockHttpServletResponse());

        MockHttpServletResponse response = new MockHttpServletResponse();
        signIn.signIn(request, response, "alice", UserRole.VIEWER, "HEADER");

        // Spring Security 7 discards the old token immediately and mints the replacement when
        // the next page asks for one (deferred); what matters is that the pre-sign-in value is
        // gone and the replacement carries a different secret.
        assertThat(repository.loadToken(request)).isNull();
        CsrfToken after = repository.loadDeferredToken(request, response).get();
        assertThat(after.getToken()).isNotEqualTo(before.getToken());
    }

    @Test
    void aSavedTargetIsReducedToAPathOnThisApplication() {
        assertThat(ExternalSessionSignIn.localPath("http://localhost:8080/projects?tab=x"))
                .isEqualTo("/projects?tab=x");
        assertThat(ExternalSessionSignIn.localPath("//evil.example/x")).isEqualTo("/x");
        assertThat(ExternalSessionSignIn.localPath("javascript:alert(1)")).isEqualTo("/");
        assertThat(ExternalSessionSignIn.localPath(null)).isEqualTo("/");
    }
}
