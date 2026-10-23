package com.qqmu.jync.service.auth;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.util.ClientIpResolver;

/**
 * Refuses a login POST while its username+IP pair is locked out, before the request reaches
 * the authentication provider.
 *
 * <p>Checking here (rather than inside a failure handler) is what makes the lockout mean
 * something: a failure handler only runs after the password has been verified, so without
 * this filter the attacker would keep testing passwords through the "locked" period.
 */
public class LoginLockoutFilter extends OncePerRequestFilter {

    /** The local form and the OA form share one counter, keyed by username + client IP. */
    private static final String LOGIN_PATH = "/login";
    private static final String OA_LOGIN_PATH = "/login/oa";

    private final LoginAttemptService attemptService;

    public LoginLockoutFilter(LoginAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getServletPath();
        boolean oa = OA_LOGIN_PATH.equals(path);
        if ("POST".equalsIgnoreCase(request.getMethod()) && (oa || LOGIN_PATH.equals(path))) {
            String username = request.getParameter("username");
            String ip = ClientIpResolver.resolve(request);
            if (username != null && attemptService.isBlocked(
                    LoginAttemptService.key(username, ip))) {
                response.sendRedirect(request.getContextPath()
                        + (oa ? "/login?oaLocked" : "/login?locked"));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
