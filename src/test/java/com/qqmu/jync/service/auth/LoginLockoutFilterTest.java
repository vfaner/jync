package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

/**
 * P1: a locked pair must be stopped before the authentication provider runs; everything else
 * passes through untouched.
 */
class LoginLockoutFilterTest {

    private LoginAttemptService attemptService;
    private OaAuthConfigService configService;
    private LoginLockoutFilter filter;

    @BeforeEach
    void setUp() {
        attemptService = new LoginAttemptService();
        configService = mock(OaAuthConfigService.class);
        when(configService.load()).thenReturn(new OaAuthConfig());
        filter = new LoginLockoutFilter(attemptService, configService);
    }

    @Test
    void blocksALockedPostAndDoesNotContinueTheChain() throws Exception {
        lock("admin", "10.0.0.9");

        MockHttpServletRequest request = loginPost("admin");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?locked");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesAnOpenPostThrough() throws Exception {
        MockHttpServletRequest request = loginPost("admin");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void doesNotLockOtherPairs() throws Exception {
        lock("admin", "10.0.0.9");

        MockHttpServletRequest request = loginPost("view");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void ignoresGetRequestsEvenWhenLocked() throws Exception {
        lock("admin", "10.0.0.9");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setServletPath("/login");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aDisabledLocalLoginStopsThePostBeforeTheProvider() throws Exception {
        OaAuthConfig config = new OaAuthConfig();
        config.setLocalLoginEnabled(Boolean.FALSE);
        when(configService.load()).thenReturn(config);
        MockHttpServletRequest request = loginPost("admin");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?localDisabled");
        assertThat(chain.getRequest()).isNull();
    }

    private void lock(String username, String ip) {
        String key = LoginAttemptService.key(username, ip);
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            attemptService.recordFailure(key);
        }
    }

    private MockHttpServletRequest loginPost(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setServletPath("/login");
        request.addParameter("username", username);
        request.addParameter("password", "wrong");
        return request;
    }
}
