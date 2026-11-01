package com.qqmu.jync.controller;

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
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;
import com.qqmu.jync.service.metrics.ProjectMetricsView;

/**
 * The task performance page: throughput, lag and failures for one project.
 *
 * <p>Both roles may open the page — a viewer watching a sync is exactly who needs the
 * throughput numbers. The JVM's own CPU, heap and GC used to be here as well; they describe
 * the whole server rather than this task, so they now live on the dashboard's admin-only
 * process card and never travel through this endpoint.
 */
@Controller
@RequestMapping("/projects/{id}")
public class ProjectPerformanceController {

    private final ProjectService projectService;
    private final DatabaseConfigService databaseConfigService;
    private final MetricsQueryService metricsQueryService;

    public ProjectPerformanceController(ProjectService projectService,
                                        DatabaseConfigService databaseConfigService,
                                        MetricsQueryService metricsQueryService) {
        this.projectService = projectService;
        this.databaseConfigService = databaseConfigService;
        this.metricsQueryService = metricsQueryService;
    }

    @GetMapping("/performance")
    public String page(@PathVariable Long id,
                       @RequestParam(required = false) String range,
                       Model model, RedirectAttributes flash) {
        Project project = projectService.findById(id).orElse(null);
        if (project == null) {
            flash.addFlashAttribute("error", "error.project.not.found");
            return "redirect:/projects";
        }
        model.addAttribute("project", project);
        model.addAttribute("metricsRange", MetricsRange.parse(range).key());
        // Both ends of the pipeline, so the header says which databases these numbers describe.
        model.addAttribute("sourceConnection", connection(project.getSourceDbId()));
        model.addAttribute("targetConnection", connection(project.getTargetDbId()));
        return "project-performance";
    }

    /**
     * The chart data behind the page, polled every few seconds.
     *
     * <p>A missing project answers 404 rather than an empty chart: an empty chart on a
     * deleted project reads as "no traffic", which is the opposite of the truth.
     */
    @GetMapping("/metrics")
    @ResponseBody
    public ResponseEntity<ProjectMetricsView> metrics(@PathVariable Long id,
                                                      @RequestParam(required = false) String range) {
        if (projectService.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProjectMetricsView view = metricsQueryService.project(id, MetricsRange.parse(range));
        return ResponseEntity.ok(view);
    }

    private DatabaseConfig connection(Long connectionId) {
        return connectionId == null ? null : databaseConfigService.findById(connectionId).orElse(null);
    }

}
