package com.qqmu.jync.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.ExternalIdentity;
import com.qqmu.jync.service.auth.ExternalSessionSignIn;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.service.auth.oa.TicketValidationClient;
import com.qqmu.jync.util.ClientIpResolver;

/**
 * The login centre's return address.
 *
 * <p>Always answers with a 302: the ticket is a credential, and rendering anything here would put
 * it in the page (and the Referer). A {@code next} parameter is deliberately not read.
 */
@Controller
public class SsoEntryController {

    private final OaAuthConfigService configService;
    private final TicketValidationClient ticketClient;
    private final ExternalSessionSignIn signIn;
    private final AdminAuditService auditService;

    public SsoEntryController(OaAuthConfigService configService,
                              TicketValidationClient ticketClient,
                              ExternalSessionSignIn signIn,
                              AdminAuditService auditService) {
        this.configService = configService;
        this.ticketClient = ticketClient;
        this.signIn = signIn;
        this.auditService = auditService;
    }

    @GetMapping("/sso/entry")
    public String entry(HttpServletRequest request, HttpServletResponse response) {
        OaAuthConfig config = configService.load();
        if (!config.isEnabled() || config.modeOrDefault() != OaAuthConfig.Mode.TICKET) {
            return "redirect:/login";
        }
        String param = StringUtils.hasText(config.getTicketParam())
                ? config.getTicketParam().trim() : "ticket";
        String ticket = request.getParameter(param);
        if (!StringUtils.hasText(ticket)) {
            return "redirect:/login?ticketMissing";
        }

        OaAuthResult result = ticketClient.validate(config, ticket.trim());
        if (!result.isSuccess()) {
            // The ticket itself is a credential: it never reaches the audit row.
            auditService.record(AuditAction.LOGIN_FAILED,
                    "Ticket sign-in from " + ClientIpResolver.resolve(request)
                            + " (" + result.getErrorKey() + ")", "sso");
            return "redirect:/login?ticketError";
        }

        UserRole role = ExternalIdentity.role(config, result.getUsername(), result.isAdmin());
        signIn.signIn(request, response, result.getUsername(), role, "TICKET");
        return "redirect:" + signIn.savedTarget(request, response);
    }
}
