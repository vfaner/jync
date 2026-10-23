package com.qqmu.jync.controller;

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
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.LoginAttemptService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.oa.OaAuthClient;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.util.ClientIpResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Signs a user in through the enterprise OA's own login API.
 *
 * <p>This handler builds the authenticated context itself, so two things are load-bearing:
 * the session id is changed before the context is written (a session fixed by an attacker must
 * not end up authenticated), and the context is persisted explicitly with
 * {@link HttpSessionSecurityContextRepository} rather than relying on the filter's save-at-end
 * behaviour.
 *
 * <p>Nothing here writes a local account: an OA user exists only as this session's principal.
 */
@Controller
@Slf4j
public class OaLoginController {

    static final String LOGIN_PATH = "/login/oa";

    private final OaAuthConfigService configService;
    private final OaAuthClient oaAuthClient;
    private final LoginAttemptService attemptService;
    private final AdminAuditService auditService;

    private final RequestCache requestCache = new HttpSessionRequestCache();

    public OaLoginController(OaAuthConfigService configService,
                             OaAuthClient oaAuthClient,
                             LoginAttemptService attemptService,
                             AdminAuditService auditService) {
        this.configService = configService;
        this.oaAuthClient = oaAuthClient;
        this.attemptService = attemptService;
        this.auditService = auditService;
    }

    @PostMapping(LOGIN_PATH)
    public String login(@RequestParam(required = false) String username,
                        @RequestParam(required = false) String password,
                        HttpServletRequest request,
                        HttpServletResponse response) {
        OaAuthConfig config = configService.load();
        if (!config.isEnabled()) {
            return "redirect:/login?oaDisabled";
        }

        String key = LoginAttemptService.key(username, ClientIpResolver.resolve(request));
        if (attemptService.isBlocked(key)) {
            return "redirect:/login?oaLocked";
        }

        OaAuthResult result = oaAuthClient.authenticate(config, username, password);
        if (!result.isSuccess()) {
            attemptService.recordFailure(key);
            // The typed username is attacker-controlled; the audit service sanitizes and caps it.
            auditService.record(AuditAction.LOGIN_FAILED,
                    "OA sign-in from " + ClientIpResolver.resolve(request), username);
            return "redirect:/login?oaError";
        }

        attemptService.recordSuccess(key);
        auditService.record(AuditAction.LOGIN,
                "Signed in from " + ClientIpResolver.resolve(request) + " via OA", result.getUsername());

        SyncUserDetails principal = SyncUserDetails.forExternalUser(result.getUsername(),
                result.isAdmin() ? UserRole.ADMIN : UserRole.VIEWER);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        // Session fixation protection. changeSessionId keeps the CSRF token and the saved
        // request while replacing the id the attacker may have fixed.
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

        // Return to whatever the user originally asked for, exactly like the local form's success
        // handler does; the saved request is single-use.
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null && saved.getRedirectUrl() != null) {
            requestCache.removeRequest(request, response);
            return "redirect:" + saved.getRedirectUrl();
        }
        return "redirect:/";
    }
}
