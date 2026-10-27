package com.qqmu.jync.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.config.I18nConfig;
import com.qqmu.jync.dto.bulk.BulkPlan;
import com.qqmu.jync.dto.bulk.BulkPlanRequest;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.bulk.BulkPlanService;

/**
 * The bulk task wizard: pick two connections and a task count, preview the binning, create
 * the tasks, or export the plan as CSV.
 *
 * <p>One form, three POSTs that all take the same fields and all recompute the plan from the
 * source, so nothing depends on server-side wizard state and a reload or a back button cannot
 * create a task from a stale preview. Creating is a single transaction inside the service;
 * every failure here is flashed back as a message key.
 *
 * <p>Admin-only: the GET is listed in {@code ADMIN_ONLY_GET} and every POST is admin-only by
 * the blanket rule, because both reading a listing and creating tasks are configuration work.
 */
@Controller
@RequestMapping("/projects/bulk")
public class BulkProjectController {

    private static final String DEFAULT_TASK_COUNT = "8";

    private final BulkPlanService bulkPlanService;
    private final DatabaseConfigService databaseConfigService;
    private final I18nConfig.MessageSourceAccessorHolder messages;

    public BulkProjectController(BulkPlanService bulkPlanService,
                                 DatabaseConfigService databaseConfigService,
                                 I18nConfig.MessageSourceAccessorHolder messages) {
        this.bulkPlanService = bulkPlanService;
        this.databaseConfigService = databaseConfigService;
        this.messages = messages;
    }

    @GetMapping
    public String form(Model model) {
        addOptions(model);
        return "bulk-plan";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam Long sourceDbId,
                          @RequestParam Long targetDbId,
                          @RequestParam(defaultValue = DEFAULT_TASK_COUNT) int taskCount,
                          @RequestParam String namePrefix,
                          @RequestParam(required = false) String captureStrategy,
                          @RequestParam(defaultValue = "false") boolean exactStats,
                          Model model) {
        BulkPlanRequest request = new BulkPlanRequest(sourceDbId, targetDbId, taskCount,
                namePrefix, blankToNull(captureStrategy), exactStats);
        try {
            model.addAttribute("plan", bulkPlanService.plan(request));
        } catch (IllegalArgumentException e) {
            // Re-render the form with the submitted values so the user fixes one field
            // instead of retyping the whole request.
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("request", request);
        addOptions(model);
        return "bulk-plan";
    }

    @PostMapping("/create")
    public String create(@RequestParam Long sourceDbId,
                         @RequestParam Long targetDbId,
                         @RequestParam(defaultValue = DEFAULT_TASK_COUNT) int taskCount,
                         @RequestParam String namePrefix,
                         @RequestParam(required = false) String captureStrategy,
                         @RequestParam(defaultValue = "false") boolean exactStats,
                         RedirectAttributes flash) {
        try {
            bulkPlanService.create(new BulkPlanRequest(sourceDbId, targetDbId, taskCount,
                    namePrefix, blankToNull(captureStrategy), exactStats));
            flash.addFlashAttribute("message", "msg.project.bulk.created");
            return "redirect:/projects";
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/bulk";
        }
    }

    /**
     * Recomputes the plan and streams it as CSV.
     *
     * <p>Returns {@code Object} because a failed recompute is a redirect to the wizard with
     * the usual error banner — a browser asked for a download must not be handed a JSON error
     * or a blank page instead.
     */
    @PostMapping("/export")
    public Object export(@RequestParam Long sourceDbId,
                         @RequestParam Long targetDbId,
                         @RequestParam(defaultValue = DEFAULT_TASK_COUNT) int taskCount,
                         @RequestParam String namePrefix,
                         @RequestParam(required = false) String captureStrategy,
                         @RequestParam(defaultValue = "false") boolean exactStats,
                         RedirectAttributes flash) {
        try {
            BulkPlan plan = bulkPlanService.plan(new BulkPlanRequest(sourceDbId, targetDbId,
                    taskCount, namePrefix, blankToNull(captureStrategy), exactStats));
            byte[] body = bulkPlanService.toCsv(plan, csvLabels())
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"bulk-plan.csv\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/projects/bulk";
        }
    }

    /** Source/target selectors list only connections classified for that role. */
    private void addOptions(Model model) {
        List<DatabaseConfig> sources = new ArrayList<>(
                databaseConfigService.findByRole(ConnectionRole.SOURCE));
        List<DatabaseConfig> targets = new ArrayList<>(
                databaseConfigService.findByRole(ConnectionRole.TARGET));
        model.addAttribute("sourceDatabases", sources);
        model.addAttribute("targetDatabases", targets);
    }

    private BulkPlanService.CsvLabels csvLabels() {
        Locale locale = LocaleContextHolder.getLocale();
        return new BulkPlanService.CsvLabels(
                List.of(
                        messages.get("project.bulk.csv.seq", locale),
                        messages.get("project.bulk.csv.task", locale),
                        messages.get("project.bulk.csv.table", locale),
                        messages.get("project.bulk.csv.rows", locale),
                        messages.get("project.bulk.csv.bytes", locale),
                        messages.get("project.bulk.csv.stats", locale)),
                messages.get("project.bulk.stats.catalog", locale),
                messages.get("project.bulk.stats.exact", locale),
                messages.get("project.bulk.stats.unknown", locale));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
