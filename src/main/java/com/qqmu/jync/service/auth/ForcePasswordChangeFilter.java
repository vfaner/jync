package com.qqmu.jync.service.auth;

import java.io.IOException;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the "still using the seeded password" warning into a gate.
 *
 * <p>The jar is a public download and both seeded accounts start on {@code 123456}, so a banner
 * that can be dismissed is only advice to whoever got there first. While the signed-in principal
 * carries {@link SyncUserDetails#isUsingDefaultPassword()}, every request except the home page and
 * the assets needed to render it is answered with a redirect home (or a 403 for {@code /api/},
 * which {@code fetch()} cannot follow usefully). The home page is where the layout opens the
 * password modal in its non-dismissible form; it is also the only page a gated account may read,
 * and the dashboard controller renders it empty.
 *
 * <p>Placed after the authentication filter so the principal is populated. The flag is resolved at
 * sign-in, and the change flow invalidates the session, so no request can observe a stale value.
 */
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    /** The change form's own URL. Still allowed as the error re-render target. */
    public static final String PASSWORD_PATH = "/account/password";

    /** Where a gated request is sent: the home page, whose layout forces the password modal. */
    public static final String HOME_PATH = "/";

    /** Message-bundle key the JSON refusal carries to the browser. */
    static final String MESSAGE_KEY = "account.error.mustChangePassword";

    /**
     * Requests the home page needs in order to render. Without these the redirect target
     * itself would be unreadable — the browser would bounce on the stylesheet and never show
     * the modal.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/css/", "/js/", "/vendor/", "/assets/");

    private static final List<String> ALLOWED_PATHS = List.of(
            HOME_PATH, "/dashboard", PASSWORD_PATH, "/login", "/logout", "/favicon.ico");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SyncUserDetails principal = gatedPrincipal();
        if (principal == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = pathWithinApplication(request);
        if (isAllowed(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/")) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"" + MESSAGE_KEY + "\"}");
        } else {
            response.sendRedirect(request.getContextPath() + HOME_PATH);
        }
    }

    private static SyncUserDetails gatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (!(auth.getPrincipal() instanceof SyncUserDetails)) {
            return null;
        }
        SyncUserDetails principal = (SyncUserDetails) auth.getPrincipal();
        return principal.isUsingDefaultPassword() ? principal : null;
    }

    private static boolean isAllowed(String path) {
        // Anything that tries to walk out of the prefix it names is treated as not allowed. The
        // deny branch redirects to the password page, so an odd path costs a hop and nothing else.
        if (path.contains("..") || path.contains(";")) {
            return false;
        }
        if (ALLOWED_PATHS.contains(path)) {
            return true;
        }
        return ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
