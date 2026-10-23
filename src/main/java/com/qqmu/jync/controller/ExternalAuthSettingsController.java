package com.qqmu.jync.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.OaAuthConfigForm;
import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.ExternalIdentity;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.auth.oa.OaAuthResult;
import com.qqmu.jync.service.auth.oa.TicketValidationClient;
import com.qqmu.jync.util.ClientIpResolver;

/**
 * The administrator's page for every way an external identity can enter this application.
 *
 * <p>Admin-only end to end ({@code SecurityConfig.ADMIN_ONLY_GET}): it holds the endpoint
 * credentials are sent to and the shared secret's configuration.
 */
@Controller
@RequestMapping("/external-auth")
public class ExternalAuthSettingsController {

    private final OaAuthConfigService configService;
    private final TicketValidationClient ticketClient;

    public ExternalAuthSettingsController(OaAuthConfigService configService,
                                          TicketValidationClient ticketClient) {
        this.configService = configService;
        this.ticketClient = ticketClient;
    }

    @GetMapping
    public String form(Model model) {
        OaAuthConfig config = configService.load();
        model.addAttribute("config", config);
        model.addAttribute("modes", OaAuthConfig.Mode.values());
        model.addAttribute("requestFormats", OaAuthConfig.RequestFormat.values());
        model.addAttribute("validateMethods", OaAuthConfig.ValidateMethod.values());
        model.addAttribute("secretSet", StringUtils.hasText(config.getSecretHeaderValue()));
        return "external-auth";
    }

    @PostMapping
    public String save(@ModelAttribute("config") OaAuthConfigForm form,
                       Model model, RedirectAttributes flash) {
        try {
            configService.save(form.toEntity(), form.getSecretHeaderValue(), form.isClearSecret());
            flash.addFlashAttribute("message", "msg.oa.saved");
            return "redirect:/external-auth";
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Keep what was typed on screen (the secret field itself is never rendered back) and
            // let the template resolve the i18n key. Nothing was written.
            model.addAttribute("modes", OaAuthConfig.Mode.values());
            model.addAttribute("requestFormats", OaAuthConfig.RequestFormat.values());
            model.addAttribute("validateMethods", OaAuthConfig.ValidateMethod.values());
            model.addAttribute("secretSet", StringUtils.hasText(form.getSecretHeaderValue())
                    || StringUtils.hasText(configService.load().getSecretHeaderValue()));
            model.addAttribute("formError", e.getMessage());
            return "external-auth";
        }
    }

    /** What the current request would map to, without creating a session. */
    @PostMapping("/test/header")
    @ResponseBody
    public Map<String, Object> testHeader(HttpServletRequest request) {
        OaAuthConfig config = configService.load();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", config.modeOrDefault().name());
        out.put("trusted", ClientIpResolver.isTrustedPeer(request.getRemoteAddr()));
        String userHeader = config.getUserHeader();
        String user = userHeader == null ? null
                : ExternalIdentity.sanitize(request.getHeader(userHeader));
        out.put("user", user);
        String adminHeader = config.getAdminHeader();
        boolean adminMarker = adminHeader != null
                && ExternalIdentity.matches(request.getHeader(adminHeader), config.getAdminValue());
        out.put("role", user == null ? null
                : ExternalIdentity.role(config, user, adminMarker).name());
        out.put("secretConfigured", config.getSecretHeaderValue() != null);
        return out;
    }

    /** One validation round-trip; the ticket and the response body are never echoed. */
    @PostMapping("/test/ticket")
    @ResponseBody
    public Map<String, Object> testTicket(@RequestParam(required = false) String ticket) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(ticket)) {
            out.put("success", false);
            out.put("error", "error.oa.ticket.required");
            return out;
        }
        OaAuthConfig config = configService.load();
        OaAuthResult result = ticketClient.validate(config, ticket.trim());
        out.put("success", result.isSuccess());
        out.put("user", result.getUsername());
        out.put("role", result.isSuccess()
                ? ExternalIdentity.role(config, result.getUsername(), result.isAdmin()).name()
                : null);
        out.put("error", result.getErrorKey());
        return out;
    }
}
