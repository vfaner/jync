package com.qqmu.jync.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.metrics.DbMetricsView;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;

/**
 * The database performance page: connection health and latency, in-database runtime counters
 * and capacity, for one configured connection.
 *
 * <p>Opens to administrators only, enforced in {@code SecurityConfig} rather than here: the
 * page dials the production database behind the connection, and its runtime counters describe
 * every session on that server, not just this application's.
 *
 * <p>An unhealthy database still opens. The latest probe failing is the reason to look at the
 * page, not a reason to refuse it — the page shows a red banner and the history up to the
 * failure; the curve simply has no new points.
 */
@Controller
@RequestMapping("/databases/{id}")
public class DatabasePerformanceController {

    /** The runtime counters whose absence means "this product reports none of them". */
    private static final List<String> RUNTIME_KEYS =
            List.of("sessions", "qps", "cacheHit", "locks");

    private final DatabaseConfigService databaseConfigService;
    private final MetricsQueryService metricsQueryService;

    public DatabasePerformanceController(DatabaseConfigService databaseConfigService,
                                         MetricsQueryService metricsQueryService) {
        this.databaseConfigService = databaseConfigService;
        this.metricsQueryService = metricsQueryService;
    }

    @GetMapping("/performance")
    public String page(@PathVariable Long id,
                       @RequestParam(required = false) String range,
                       Model model, RedirectAttributes flash) {
        DatabaseConfig config = databaseConfigService.findById(id).orElse(null);
        if (config == null) {
            flash.addFlashAttribute("error", "error.connection.missing");
            return "redirect:/databases";
        }
        MetricsRange window = MetricsRange.parse(range);
        DbMetricsView view = metricsQueryService.database(id, window);
        model.addAttribute("config", config);
        model.addAttribute("metrics", view);
        model.addAttribute("metricsRange", window.key());
        // Drives the placeholder: with none of the four runtime figures readable, the page says
        // so instead of drawing four zeros that would claim a perfectly idle database.
        model.addAttribute("runtimeSupported", hasRuntimeFigures(view));
        return "database-performance";
    }

    @GetMapping("/metrics")
    @ResponseBody
    public ResponseEntity<DbMetricsView> metrics(@PathVariable Long id,
                                                 @RequestParam(required = false) String range) {
        if (databaseConfigService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metricsQueryService.database(id, MetricsRange.parse(range)));
    }

    private static boolean hasRuntimeFigures(DbMetricsView view) {
        Map<String, Double> current = view.current();
        for (String key : RUNTIME_KEYS) {
            if (current.get(key) != null) {
                return true;
            }
        }
        return false;
    }
}
