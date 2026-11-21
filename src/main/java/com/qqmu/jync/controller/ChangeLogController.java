package com.qqmu.jync.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcDdlTaskService;

import lombok.extern.slf4j.Slf4j;

/** Paged, filterable view of the change log. */
@Controller
@Slf4j
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
        List<Project> projects = projectService.findAll();
        model.addAttribute("projects", projects);
        // The skipped-DDL button posts to a handler that requires editing the log's project.
        // Rendering it for a foreign project would hand the operator a button whose answer is
        // a 302 the page's fetch cannot parse; an administrator passes that check everywhere.
        AccessGuard actor = AccessGuard.current();
        model.addAttribute("editableProjectIds", projects.stream()
                .filter(project -> actor.canEdit(project.getOwnerUsername()))
                .map(Project::getId)
                .collect(Collectors.toSet()));
        model.addAttribute("selectedProjectId", projectId);
        // A binlog DDL qualifies the table with the source database; the target's own name
        // is what the statement has to run against.
        model.addAttribute("targetDatabaseName", ddlTaskService == null
                ? null : ddlTaskService.targetDatabaseName(projectId));
        model.addAttribute("failedOnly", errorsOnly);
        // The purge picker may not offer today: today's rows always survive a date purge.
        model.addAttribute("purgeMaxDate",
                com.qqmu.jync.util.LogPurgeDates.maxSelectable().toString());
        return "change-logs";
    }

    /**
     * Date-based purge of change-log entries, scoped like the page: a project filter narrows
     * to that project, the errors-only filter narrows to failed entries. The selected day is
     * included; today always survives and open DDL tasks are never removed. Answers JSON so
     * the modal can toast the localized count without a page round-trip.
     */
    @PostMapping("/change-logs/purge")
    @ResponseBody
    public Map<String, Object> purge(@RequestParam("date") String date,
                                     @RequestParam(required = false) Long projectId,
                                     @RequestParam(required = false, defaultValue = "false")
                                     Boolean failedOnly) {
        java.time.LocalDate day;
        try {
            day = java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            return Map.of("success", false, "message", text("log.purge.date.invalid"));
        }
        try {
            int removed = changeLogService.purgeBefore(day, projectId,
                    Boolean.TRUE.equals(failedOnly));
            return Map.of("success", true, "message", text("log.purge.success", removed));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Change-log purge failed: {}", e.toString());
            return Map.of("success", false, "message", text("error.unexpected"));
        }
    }

    /**
     * Executes an operator-reviewed DDL for a skipped CDC change on the target database.
     * The filter chain admits a writer here; the service then checks the log's project
     * owner. The target connection is resolved from the log's own project, never from
     * request parameters.
     */
    @PostMapping("/change-logs/{id}/execute")
    @ResponseBody
    public Map<String, Object> executeDdl(@PathVariable Long id,
                                          @RequestParam("sql") String sql) {
        try {
            if (ddlTaskService == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            CdcDdlTaskService.DdlTaskOutcome outcome = ddlTaskService.executeDdl(id, sql, AccessGuard.current());
            if (outcome == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            return Map.of("success", true, "message", text("msg.ddl.executed"),
                    "needsReplay", outcome.needsReplay(),
                    "affectedTables", outcome.affectedTables() == null
                            ? "" : outcome.affectedTables());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // The AJAX caller cannot read an HTML redirect: return a JSON refusal instead.
            return Map.of("success", false, "message", text("error.forbidden"));
        } catch (RuntimeException e) {
            log.warn("DDL task action failed unexpectedly: {}", e.toString());
            return Map.of("success", false, "message", text("error.unexpected"));
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
            ddlTaskService.replayDdl(id, AccessGuard.current());
            return Map.of("success", true, "message", text("msg.ddl.replayed"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // The AJAX caller cannot read an HTML redirect: return a JSON refusal instead.
            return Map.of("success", false, "message", text("error.forbidden"));
        } catch (RuntimeException e) {
            log.warn("DDL task action failed unexpectedly: {}", e.toString());
            return Map.of("success", false, "message", text("error.unexpected"));
        }
    }

    /**
     * Closes a skipped-DDL task without running anything: the table may already have been
     * aligned by hand, or the affected rows may not matter. Otherwise the task stays open
     * forever and keeps the column projection on.
     */
    @PostMapping("/change-logs/{id}/dismiss")
    @ResponseBody
    public Map<String, Object> dismissDdl(@PathVariable Long id) {
        try {
            if (ddlTaskService == null) {
                throw new IllegalStateException("error.ddl.notFound");
            }
            ddlTaskService.dismissDdl(id, AccessGuard.current());
            return Map.of("success", true, "message", text("msg.ddl.dismissed"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // The AJAX caller cannot read an HTML redirect: return a JSON refusal instead.
            return Map.of("success", false, "message", text("error.forbidden"));
        } catch (RuntimeException e) {
            log.warn("DDL task action failed unexpectedly: {}", e.toString());
            return Map.of("success", false, "message", text("error.unexpected"));
        }
    }

    /** Localised sentence with {0}-style arguments, for counts in the AJAX toast. */
    private String text(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException ignored) {
            return key;
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
