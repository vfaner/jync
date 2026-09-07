package com.synctool.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.synctool.service.auth.AppUserService;
import com.synctool.service.auth.SyncUserDetails;

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

    public AuthController(AppUserService userService) {
        this.userService = userService;
    }

    /**
     * Renders the sign-in form.
     *
     * <p>The {@link CsrfToken} parameter is not decoration and must not be "cleaned up". Spring
     * Security hands the template a lazy token, and resolving it creates the HTTP session that
     * stores it. This page inlines the ~27 KB icon sprite, which overflows Tomcat's default 8 KB
     * response buffer long before the renderer reaches the form near the bottom — so by the time
     * {@code th:action} asked for the token, the response was already committed and the session
     * could no longer be created, killing the render mid-page with {@code IllegalStateException}.
     * Touching the token here forces the session while nothing has been written yet.
     */
    @GetMapping("/login")
    public String login(CsrfToken csrfToken) {
        csrfToken.getToken();
        return "login";
    }

    @GetMapping("/account/password")
    public String passwordForm(Model model) {
        model.addAttribute("activeNav", "account");
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
        try {
            userService.changeOwnPassword(principal.getUsername(), currentPassword, newPassword,
                    confirmPassword);
        } catch (IllegalArgumentException e) {
            // Re-render rather than redirect: a flash attribute would need the session we are
            // about to invalidate on the success path, and two different mechanisms for the two
            // outcomes is more moving parts than this page deserves.
            model.addAttribute("errorKey", e.getMessage());
            model.addAttribute("activeNav", "account");
            return "account-password";
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?changed";
    }
}
