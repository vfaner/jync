package com.qqmu.jync.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;

/** Paged, filterable view of the change log. */
@Controller
public class ChangeLogController {

    private final ChangeLogService changeLogService;
    private final ProjectService projectService;

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
        model.addAttribute("failedOnly", errorsOnly);
        return "change-logs";
    }

    /**
     * Clears change-log entries. When a project filter is active, only that project's logs
     * are removed; otherwise the entire audit trail is wiped.
     */
    @PostMapping("/change-logs/clear")
    public String clear(@RequestParam(required = false) Long projectId,
                        @RequestParam(required = false, defaultValue = "false") Boolean failedOnly,
                        RedirectAttributes flash) {
        changeLogService.clear(projectId);
        flash.addFlashAttribute("message", "log.clear.success");
        StringBuilder url = new StringBuilder("/change-logs");
        boolean hasProject = projectId != null;
        if (hasProject) {
            url.append("?projectId=").append(projectId);
        }
        if (Boolean.TRUE.equals(failedOnly)) {
            url.append(hasProject ? '&' : '?').append("failedOnly=true");
        }
        return "redirect:" + url;
    }
}
