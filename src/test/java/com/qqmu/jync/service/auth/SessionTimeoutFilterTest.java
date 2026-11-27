package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.qqmu.jync.service.settings.SessionTimeoutService;

class SessionTimeoutFilterTest {

    @Test
    void appliesTheCurrentTimeoutToAnAuthenticatedSession() throws Exception {
        SessionTimeoutService timeouts = mock(SessionTimeoutService.class);
        when(timeouts.currentMinutes()).thenReturn(720);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
        request.setSession(session);

        new SessionTimeoutFilter(timeouts).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(720 * 60);
    }

    @Test
    void doesNotCreateOrTouchAnAnonymousSession() throws Exception {
        SessionTimeoutService timeouts = mock(SessionTimeoutService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();

        new SessionTimeoutFilter(timeouts).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(request.getSession(false)).isNull();
    }
}
