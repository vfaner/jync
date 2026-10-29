package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.util.CryptoUtil;

class HeaderSsoAuthenticationFilterTest {

    private OaAuthConfigService configService;
    private AdminAuditService auditService;
    private CryptoUtil crypto;
    private SsoHeaderAuthenticationFilter filter;
    private OaAuthConfig config;

    @BeforeEach
    void setUp() {
        configService = mock(OaAuthConfigService.class);
        auditService = mock(AdminAuditService.class);
        SyncProperties properties = new SyncProperties();
        properties.setCryptoPassword("test-password");
        properties.setCryptoSalt("0011223344556677");
        crypto = new CryptoUtil(properties);
        filter = new SsoHeaderAuthenticationFilter(
                configService, new ExternalSessionSignIn(auditService), crypto);

        config = new OaAuthConfig();
        config.setEnabled(true);
        config.setMode(OaAuthConfig.Mode.HEADER);
        config.setUserHeader("X-Auth-User");
        when(configService.load()).thenReturn(config);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String path, String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setRemoteAddr(peer);
        return request;
    }

    private static MockHttpSession session(MockHttpServletRequest request) {
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        return session;
    }

    @Test
    void aTrustedPeerWithTheIdentityHeaderBecomesASession() throws Exception {
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        MockHttpSession session = session(request);
        String before = session.getId();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(session.getId()).isNotEqualTo(before);
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo("alice");
        verify(auditService).record(any(AuditAction.class), anyString(), anyString());
    }

    @Test
    void aPeerOutsideTheTrustedListCannotUseTheHeader() throws Exception {
        MockHttpServletRequest request = request("/", "203.0.113.9");
        request.addHeader("X-Auth-User", "mallory");
        session(request);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(auditService, never()).record(any(AuditAction.class), anyString(), anyString());
    }

    @Test
    void anAuthenticatedRequestKeepsItsPrincipal() throws Exception {
        SyncUserDetails local = SyncUserDetails.forExternalUser("local", UserRole.VIEWER);
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        local, null, local.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        session(request);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("local");
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anAuthenticatedRequestNeverTouchesTheMetadataStore() throws Exception {
        // The filter runs on every page and every API call. It has nothing to do for a request
        // that already carries a principal, so reading the SSO configuration first charged a
        // metadata-store query to every authenticated request just to learn it could stand
        // down. The already-authenticated check must come before the load.
        SyncUserDetails local = SyncUserDetails.forExternalUser("local", UserRole.VIEWER);
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        local, null, local.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = request("/projects", "127.0.0.1");
        session(request);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(configService, never()).load();
    }

    @Test
    void aWrongSharedSecretIsRefused() throws Exception {
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue(crypto.encrypt("s3cr3t"));
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Secret", "wrong");
        session(request);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theMatchingSharedSecretLetsTheIdentityThrough() throws Exception {
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue(crypto.encrypt("s3cr3t"));
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Secret", "s3cr3t");
        session(request);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anUndecryptableSecretFailsClosed() throws Exception {
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue(CryptoUtil.PREFIX + "not-a-ciphertext");
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Secret", "anything");
        session(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void theAdminHeaderGrantsTheAdminRole() throws Exception {
        config.setAdminHeader("X-Auth-Admin");
        config.setAdminValue("true,1");
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Admin", "true");
        MockHttpSession session = session(request);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    void theAdminUserListGrantsTheAdminRole() throws Exception {
        config.setAdminUsers("root");
        MockHttpServletRequest request = request("/", "127.0.0.1");
        request.addHeader("X-Auth-User", "root");
        MockHttpSession session = session(request);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        SyncUserDetails principal = (SyncUserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    void aMissingIdentityHeaderSendsTheBrowserToThePortal() throws Exception {
        config.setLoginEntryUrl("https://portal.example.com/login");
        MockHttpServletRequest request = request("/", "127.0.0.1");
        session(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getRedirectedUrl()).isEqualTo("https://portal.example.com/login");
    }

    @Test
    void theLoginPagesAndAssetsAreNeverAuthenticatedByTheFilter() throws Exception {
        MockHttpServletRequest request = request("/login", "127.0.0.1");
        request.addHeader("X-Auth-User", "alice");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
