package com.qqmu.jync.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcDdlTaskService;

/** Paged, filterable view of the change log. */
@Controller
public class ChangeLogController {

    private final ChangeLogService changeLogService;
    private final ProjectService projectService;

    /**
     * The DDL tasks a skipped statement opens live in their own service. The field is
     * optional so the WebMvcTest slices that only exercise the log view still wire the
     * controller; in the running application it is always present.
     */
    @Autowired(required = false)
    private CdcDdlTaskService ddlTaskService;

    /** Optional so @WebMvcTest slices that omit the message source still wire the controller. */
    @Autowired(required = false)
    private MessageSource messageSource;

    public ChangeLogController(ChangeLogService changeLogService,
                               ProjectService projectService) {
        this.changeLogService = changeLogService;
        this.projectService = projectService;
    }

    @GetMapping("/change-logs")
    public String list(@RequestParam(required = false) Long projectId,
                       @RequestParam(required = false, defaultValue = "false") Boolean failedOnly,
                       @RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer spanL,
                       @RequestParam(required = false) Integer spanR,
                       Model model) {
        boolean errorsOnly = Boolean.TRUE.equals(failedOnly);
        // Count first: the pager clamps the requested page to the real page count, and the
        // clamped value is what the row query must use.
        Pager pager = Pager.of(page, size,
                changeLogService.count(projectId, errorsOnly), spanL, spanR);
        Pageable pageable = PageRequest.of(pager.getPage() - 1, pager.getSize());
        Page<ChangeLog> logs = changeLogService.page(projectId, errorsOnly, pageable);

        model.addAttribute("logs", logs);
        model.addAttribute("pager", pager);
        model.addAttribute("projects", projectService.findAll());
        model.addAttribute("selectedProjectId", projectId);
        // A binlog DDL qualifies the table with the source database; the target's own name
        // is what the statement has to run against.
        model.addAttribute("targetDatabaseName", ddlTaskService == null
                ? null : ddlTaskService.targetDatabaseName(projectId));
        model.addAttribute("failedOnly", errorsOnly);
        return "change-logs";
    }

    /**
     * Clears change-log entries. When a project filter is active, only that project's logs
     * are removed; otherwise the entire audit trail is wiped. Under the errors-only filter
     * the clear removes just the failed entries the user is actually looking at.
     */
    @PostMapping("/change-logs/clear")
    public String clear(@RequestParam(required = false) Long projectId,
                        @RequestParam(required = false, defaultValue = "false") Boolean failedOnly,
                        RedirectAttributes flash) {
        boolean failedOnlyFilter = Boolean.TRUE.equals(failedOnly);
        changeLogService.clear(projectId, failedOnlyFilter);
        flash.addFlashAttribute("message", "log.clear.success");
        StringBuilder url = new StringBuilder("/change-logs");
        boolean hasProject = projectId != null;
        if (hasProject) {
            url.append("?projectId=").append(projectId);
        }
        if (failedOnlyFilter) {
            url.append(hasProject ? '&' : '?').append("failedOnly=true");
        }
        return "redirect:" + url;
    }

    /**
     * Executes an administrator-edited DDL for a skipped CDC change on the target database.
     * POST endpoints are ADMIN-only in the security filter chain; the service resolves the
     * target connection from the log's own project, never from request parameters.
     */
    @PostMapping("/change-logs/{id}/execute")
    @ResponseBody
    public Map<String, Object> executeDdl(@PathVariable Long id,
                                          @RequestParam("sql") String sql) {
        try {
            if (ddlTaskService == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            CdcDdlTaskService.DdlTaskOutcome outcome = ddlTaskService.executeDdl(id, sql);
            if (outcome == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            return Map.of("success", true, "message", text("msg.ddl.executed"),
                    "needsReplay", outcome.needsReplay(),
                    "affectedTables", outcome.affectedTables() == null
                            ? "" : outcome.affectedTables());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        }
    }

    /**
     * Replays the rows that were not replicated while the target was missing a column.
     * The affected tables are re-read from the source and upserted over the target; the
     * target is never truncated.
     */
    @PostMapping("/change-logs/{id}/retry")
    @ResponseBody
    public Map<String, Object> retryDdl(@PathVariable Long id) {
        try {
            if (ddlTaskService == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            ddlTaskService.replayDdl(id);
            return Map.of("success", true, "message", text("msg.ddl.replayed"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        }
    }

    /** Resolves "key" / "key:detail" into a localised sentence for the AJAX toast. */
    private String text(String key) {
        if (key == null) {
            return "";
        }
        int colon = key.indexOf(':');
        String head = colon >= 0 ? key.substring(0, colon) : key;
        String suffix = colon >= 0 ? key.substring(colon + 1) : "";
        String translated = head;
        if (messageSource != null) {
            try {
                translated = messageSource.getMessage(head, null,
                        LocaleContextHolder.getLocale());
            } catch (NoSuchMessageException ignored) {
                // Keep the key as a readable fallback when a slice has no bundles.
            }
        }
        return suffix.isEmpty() ? translated : translated + ": " + suffix;
    }
}
