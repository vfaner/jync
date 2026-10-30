package com.qqmu.jync.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;

/** Renders the dashboard and the three fragments that keep it live. */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final SystemMetrics systemMetrics;

    public DashboardController(DashboardService dashboardService, SystemMetrics systemMetrics) {
        this.dashboardService = dashboardService;
        this.systemMetrics = systemMetrics;
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
            DashboardService.Stats stats = dashboardService.collect();
            model.addAttribute("stats", stats);
            // Same list, two names: the feed fragment is also rendered on its own (below),
            // where there is no Stats to read it from.
            model.addAttribute("recentChanges", stats.getRecentChanges());
            // Sampled with the statistics: one reading per page, then the row polls itself.
            model.addAttribute("metrics", systemMetrics.snapshot());
        }
        return "dashboard";
    }

    /**
     * The 资源使用 row alone, for the dashboard's 5s auto-refresh (app.js bindResourceRefresh).
     *
     * <p>Deliberately not {@link #dashboard}: the statistics query the repositories and the task
     * store, none of which a gauge needs. This handler samples four counters and renders the
     * fragment. A seeded-password account is refused upstream by the password gate, and its
     * page never renders the row, so it never polls.
     */
    @GetMapping("/dashboard/resource-gauges")
    public String resourceGaugesFragment(Model model) {
        model.addAttribute("metrics", systemMetrics.snapshot());
        return "dashboard :: resourceGauges";
    }

    /** The four stat tiles alone, for the dashboard's 10s auto-refresh. */
    @GetMapping("/dashboard/live-stats")
    public String liveStatsFragment(Model model) {
        model.addAttribute("stats", dashboardService.collect());
        return "dashboard :: statGrid";
    }

    /** The activity feed alone, for the dashboard's 10s auto-refresh — one indexed query. */
    @GetMapping("/dashboard/activity-feed")
    public String activityFeedFragment(Model model) {
        model.addAttribute("recentChanges", dashboardService.recentChanges());
        return "dashboard :: activityFeedList";
    }

    /** The trend chart's 24 hourly buckets, drawn by app.js (bindActivityChart). */
    @GetMapping("/dashboard/activity-series")
    @ResponseBody
    public DashboardService.ActivitySeries activitySeries() {
        return dashboardService.activitySeries();
    }
}
