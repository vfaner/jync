package com.qqmu.jync.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.service.auth.AppUserService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;

import lombok.extern.slf4j.Slf4j;

/**
 * The sign-in page and the change-your-own-password page.
 *
 * <p>There is no handler for {@code POST /login}: Spring Security's filter intercepts that URL
 * before it reaches the dispatcher. Only the GET that renders the form lives here.
 */
@Controller
@Slf4j
public class AuthController {

    private final AppUserService userService;
    private final OaAuthConfigService configService;

    public AuthController(AppUserService userService, OaAuthConfigService configService) {
        this.userService = userService;
        this.configService = configService;
    }

    /**
     * Routes the sign-in entry point according to the configured authentication mode.
     *
     * <p>The {@link CsrfToken} parameter is not decoration and must not be "cleaned up". Spring
     * Security hands the template a lazy token, and resolving it creates the HTTP session that
     * stores it. This page inlines the ~27 KB icon sprite, which overflows Tomcat's default 8 KB
     * response buffer long before the renderer reaches the form near the bottom — so by the time
     * {@code th:action} asked for the token, the response was already committed and the session
     * could no longer be created, killing the render mid-page with {@code IllegalStateException}.
     * Touching the token here forces the session while nothing has been written yet.
     *
     * <p>Only the PASSWORD mode renders a password form here. The external modes hand the visitor
     * to the portal (when one is configured) or to a page that explains where to enter; the
     * local form stays reachable at {@code /login/local} for the break-glass administrator.
     */
    @GetMapping("/login")
    public String login(CsrfToken csrfToken, Model model, HttpServletRequest request) {
        csrfToken.getToken();
        OaAuthConfig config = configService.load();
        if (!config.isEnabled()) {
            return "redirect:/login/local";
        }
        if (config.modeOrDefault() == OaAuthConfig.Mode.PASSWORD) {
            return localForm(model, config, true);
        }
        // A ticket outcome must be readable: bouncing the browser to the portal would hide the
        // one message that tells the user what happened — including the lockout, which would
        // otherwise send a throttled caller straight back to the portal to retry. Presence, not
        // value: these arrive as bare query flags (/login?ticketError), whose value is empty or
        // null depending on the container.
        boolean ticketFeedback = request.getParameterValues("ticketError") != null
                || request.getParameterValues("ticketMissing") != null
                || request.getParameterValues("ticketLocked") != null;
        if (!ticketFeedback && StringUtils.hasText(config.getLoginEntryUrl())) {
            return "redirect:" + config.getLoginEntryUrl();
        }
        model.addAttribute("localEnabled", config.isLocalLoginAllowed());
        return "login-external";
    }

    /**
     * The local account form, always rendered whatever the mode is — it is the administrator's
     * way back in when the portal is unreachable, and the only form when OA is disabled.
     */
    @GetMapping("/login/local")
    public String localLogin(CsrfToken csrfToken, Model model) {
        // Same reason as /login: the token must exist before the template renders the form,
        // otherwise the first paint and the posted token can belong to different sessions.
        csrfToken.getToken();
        return localForm(model, configService.load(), false);
    }

    private static String localForm(Model model, OaAuthConfig config, boolean showOa) {
        boolean localEnabled = config.isLocalLoginAllowed();
        model.addAttribute("showLocal", localEnabled);
        model.addAttribute("showOa", showOa && config.isEnabled()
                && config.modeOrDefault() == OaAuthConfig.Mode.PASSWORD);
        model.addAttribute("localDisabled", !localEnabled);
        return "login";
    }

    @GetMapping("/account/password")
    public String passwordForm(@AuthenticationPrincipal SyncUserDetails principal) {
        if (isExternal(principal)) {
            return "redirect:/?denied";
        }
        return "account-password";
    }

    /**
     * Changes the signed-in user's password and then ends the session.
     *
     * <p>Ending it is deliberate. The credential the session was established with no longer
     * exists, and the principal it carries holds a now-stale "using the default password" flag
     * that drives a security warning. Re-authenticating is both the honest thing to do and the
     * cheapest way to keep that flag truthful.
     */
    @PostMapping("/account/password")
    public String changePassword(@AuthenticationPrincipal SyncUserDetails principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 HttpServletRequest request,
                                 Model model) {
        if (isExternal(principal)) {
            return "redirect:/?denied";
        }
        try {
            userService.changeOwnPassword(principal.getUsername(), currentPassword, newPassword,
                    confirmPassword);
        } catch (IllegalArgumentException e) {
            // Re-render rather than redirect: a flash attribute would need the session we are
            // about to invalidate on the success path, and two different mechanisms for the two
            // outcomes is more moving parts than this page deserves.
            model.addAttribute("errorKey", e.getMessage());
            return "account-password";
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?changed";
    }

    /** OA principals have no local password; both handlers refuse them before touching the DB. */
    private static boolean isExternal(SyncUserDetails principal) {
        return principal != null && principal.isExternal();
    }
}
