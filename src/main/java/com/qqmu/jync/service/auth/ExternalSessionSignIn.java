package com.qqmu.jync.service.auth;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.util.AuditOrigin;

/**
 * The one place that turns a verified external identity into a session.
 *
 * <p>Two properties are load-bearing and must survive refactoring: the session id is changed
 * before the context is written (a session fixed by an attacker must not end up authenticated),
 * and the context is persisted explicitly rather than relying on the filter's save-at-end.
 */
@Component
public class ExternalSessionSignIn {

    /**
     * The application's CSRF token store, shared by every sign-in. It is the same session
     * attribute the servlet filter reads, so a stateless instance per call would work too, but
     * one instance keeps the parameter/header names in one place.
     */
    private static final HttpSessionCsrfTokenRepository CSRF_TOKENS =
            new HttpSessionCsrfTokenRepository();

    private static final HttpSessionSecurityContextRepository CONTEXT_STORE =
            new HttpSessionSecurityContextRepository();

    private final AdminAuditService auditService;
    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final SessionAuthenticationStrategy csrfStrategy =
            new CsrfAuthenticationStrategy(CSRF_TOKENS);

    public ExternalSessionSignIn(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    public SyncUserDetails signIn(HttpServletRequest request, HttpServletResponse response,
                                  String username, UserRole role, String method) {
        SyncUserDetails principal = SyncUserDetails.forExternalUser(username, role);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        HttpSession existing = request.getSession(false);
        if (existing != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        CONTEXT_STORE.saveContext(context, request, response);
        // Rotate the token handed out before sign-in: it was minted for an anonymous session
        // and must not keep driving the authenticated one. Form login already does this through
        // the session-authentication strategy; this manual path has to ask for it.
        csrfStrategy.onAuthentication(authentication, request, response);

        auditService.record(AuditAction.LOGIN,
                AuditDetail.of("audit.detail.loginVia", AuditOrigin.of(request),
                        "@method:" + method.toLowerCase(java.util.Locale.ROOT)),
                username);
        return principal;
    }

    /** The pre-sign-in request when it is a path in this application; {@code /} otherwise. */
    public String savedTarget(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved == null) {
            return "/";
        }
        requestCache.removeRequest(request, response);
        return localPath(saved.getRedirectUrl());
    }

    /**
     * A redirect target taken from session state is still reduced to a path, so no stored absolute
     * URL and no protocol-relative one can send the browser to another host.
     */
    static String localPath(String redirectUrl) {
        if (redirectUrl == null) {
            return "/";
        }
        try {
            URI uri = URI.create(redirectUrl);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return "/";
            }
            String query = uri.getRawQuery();
            return query == null ? path : path + "?" + query;
        } catch (IllegalArgumentException ex) {
            return "/";
        }
    }
}
