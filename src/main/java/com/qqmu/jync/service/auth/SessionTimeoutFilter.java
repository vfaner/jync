package com.qqmu.jync.service.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.service.settings.SessionTimeoutService;

/**
 * Applies a changed session timeout to sessions that already exist. The servlet context setter
 * only affects sessions created later; an administrator who raises the timeout should not have
 * to log every online operator out first.
 */
@Component
public class SessionTimeoutFilter extends OncePerRequestFilter {

    private final ObjectProvider<SessionTimeoutService> provider;
    private final SessionTimeoutService direct;

    /**
     * The provider keeps WebMvcTest slices (which do not load the settings service) from
     * failing the filter's constructor. Production always has the service.
     */
    @Autowired
    public SessionTimeoutFilter(ObjectProvider<SessionTimeoutService> provider) {
        this.provider = provider;
        this.direct = null;
    }

    /** Test seam: a fixed service without a Spring context. */
    SessionTimeoutFilter(SessionTimeoutService timeouts) {
        this.provider = null;
        this.direct = timeouts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SessionTimeoutService timeouts = direct != null
                ? direct : provider.getIfAvailable();
        HttpSession session = request.getSession(false);
        if (timeouts != null && session != null
                && session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null) {
            session.setMaxInactiveInterval(timeouts.currentMinutes() * 60);
        }
        filterChain.doFilter(request, response);
    }
}
