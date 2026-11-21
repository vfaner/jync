package com.qqmu.jync.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.HostInfo;
import com.qqmu.jync.service.HostTopProcesses;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.AppMetricsView;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;

/** Renders the dashboard and the fragments that keep it live. */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final SystemMetrics systemMetrics;
    private final MetricsQueryService metricsQueryService;
    private final HostInfo hostInfo;
    private final HostTopProcesses hostTopProcesses;

    public DashboardController(DashboardService dashboardService, SystemMetrics systemMetrics,
                               MetricsQueryService metricsQueryService, HostInfo hostInfo,
                               HostTopProcesses hostTopProcesses) {
        this.dashboardService = dashboardService;
        this.systemMetrics = systemMetrics;
        this.metricsQueryService = metricsQueryService;
        this.hostInfo = hostInfo;
        this.hostTopProcesses = hostTopProcesses;
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
            model.addAttribute("host", hostInfo.cpu());
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
        model.addAttribute("host", hostInfo.cpu());
        return "dashboard :: resourceGauges";
    }

    /**
     * The busiest processes on this host, for the CPU gauge's expandable hover card.
     *
     * <p>Read on demand (one click), cached for 10 seconds server-side: the 5-second gauge
     * refresh must not turn into a process-table poll. Only executable names are returned —
     * command lines in this product carry {@code -D} passwords.
     */
    @GetMapping("/dashboard/top-processes")
    @ResponseBody
    public HostTopProcesses.TopProcesses topProcesses() {
        return hostTopProcesses.topCpu(5);
    }

    /**
     * The stat tiles and the project overview together, for the dashboard's 10s auto-refresh.
     *
     * <p>Both come from one {@link DashboardService#collect()} — the overview only exists in
     * the statistics it already counts — so the poll costs exactly what the tiles alone did.
     * The overview is here because it has no refresh address of its own: the toolbar's manual
     * refresh button was its only update path.
     */
    @GetMapping("/dashboard/live-stats")
    public String liveStatsFragment(Model model) {
        model.addAttribute("stats", dashboardService.collect());
        return "dashboard :: liveStats";
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
    public DashboardService.MultiSeriesActivity activitySeries() {
        return dashboardService.activitySeries();
    }

    /**
     * The process resources this JVM shares with every task on the server, for the dashboard
     * card. Readable by every authenticated role: these figures describe the whole machine
     * rather than any one account, which is why they are not attached to any one project.
     */
    @GetMapping("/dashboard/process-metrics")
    @ResponseBody
    public AppMetricsView processMetrics(@RequestParam(required = false) String range) {
        return metricsQueryService.app(MetricsRange.parse(range));
    }
}
