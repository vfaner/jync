package com.qqmu.jync.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.ExternalIdentity;
import com.qqmu.jync.service.auth.ExternalSessionSignIn;
import com.qqmu.jync.service.auth.LoginAttemptService;
import com.qqmu.jync.service.auth.oa.OaAuthClient;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.util.ClientIpResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Signs a user in through the enterprise OA's own login API.
 *
 * <p>The session itself is built by {@link ExternalSessionSignIn}, which is shared with the
 * header and ticket modes; the load-bearing properties (session-id change before the context
 * is written, explicit persistence) live there.
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
    private final ExternalSessionSignIn signIn;

    public OaLoginController(OaAuthConfigService configService,
                             OaAuthClient oaAuthClient,
                             LoginAttemptService attemptService,
                             AdminAuditService auditService,
                             ExternalSessionSignIn signIn) {
        this.configService = configService;
        this.oaAuthClient = oaAuthClient;
        this.attemptService = attemptService;
        this.auditService = auditService;
        this.signIn = signIn;
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
        // The configured admin list applies here too; the OA response marker is the other way in.
        UserRole role = ExternalIdentity.role(config, result.getUsername(), result.isAdmin());
        signIn.signIn(request, response, result.getUsername(), role, "OA");
        // Return to whatever the user originally asked for, exactly like the local form's success
        // handler does; the saved request is single-use.
        return "redirect:" + signIn.savedTarget(request, response);
    }
}
