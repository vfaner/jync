package com.qqmu.jync.service.auth;

import java.net.URI;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.util.ClientIpResolver;

/**
 * The one place that turns a verified external identity into a session.
 *
 * <p>Two properties are load-bearing and must survive refactoring: the session id is changed
 * before the context is written (a session fixed by an attacker must not end up authenticated),
 * and the context is persisted explicitly rather than relying on the filter's save-at-end.
 */
@Component
public class ExternalSessionSignIn {

    private final AdminAuditService auditService;
    private final RequestCache requestCache = new HttpSessionRequestCache();

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
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);

        auditService.record(AuditAction.LOGIN,
                "Signed in from " + ClientIpResolver.resolve(request) + " via " + method,
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
