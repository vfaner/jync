package com.qqmu.jync.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/** Renders the dashboard. */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Renders the dashboard, except for an account still on the seeded password.
     *
     * <p>That account is allowed this one page so the layout can force the change-password
     * modal on it, but the page itself must stay empty: the seeded password is public, and the
     * statistics are exactly the kind of data the gate exists to keep from it. The template
     * renders nothing when {@code stats} is absent.
     */
    @GetMapping({"/", "/dashboard"})
    public String dashboard(@AuthenticationPrincipal SyncUserDetails principal, Model model) {
        if (principal == null || !principal.isUsingDefaultPassword()) {
            model.addAttribute("stats", dashboardService.collect());
        }
        return "dashboard";
    }
}
