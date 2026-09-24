package com.qqmu.jync.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.service.ProjectService;

/** Paged, filterable view of the change log. */
@Controller
public class ChangeLogController {

    private final ChangeLogRepository changeLogRepository;
    private final ProjectService projectService;

    public ChangeLogController(ChangeLogRepository changeLogRepository,
                               ProjectService projectService) {
        this.changeLogRepository = changeLogRepository;
        this.projectService = projectService;
    }

    @GetMapping("/change-logs")
    public String list(@RequestParam(required = false) Long projectId,
                       @RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer spanL,
                       @RequestParam(required = false) Integer spanR,
                       Model model) {
        // Count first: the pager clamps the requested page to the real page count, and the
        // clamped value is what the row query must use.
        long total = projectId != null
                ? changeLogRepository.countByProjectId(projectId)
                : changeLogRepository.count();
        Pager pager = Pager.of(page, size, total, spanL, spanR);
        Pageable pageable = PageRequest.of(pager.getPage() - 1, pager.getSize());
        Page<ChangeLog> logs = projectId != null
                ? changeLogRepository.findByProjectIdOrderByOccurredAtDesc(projectId, pageable)
                : changeLogRepository.findAllByOrderByOccurredAtDesc(pageable);

        model.addAttribute("logs", logs);
        model.addAttribute("pager", pager);
        model.addAttribute("projects", projectService.findAll());
        model.addAttribute("selectedProjectId", projectId);
        model.addAttribute("activeNav", "change-logs");
        return "change-logs";
    }

    /**
     * Clears change-log entries. When a project filter is active, only that project's logs
     * are removed; otherwise the entire audit trail is wiped.
     */
    @PostMapping("/change-logs/clear")
    @Transactional
    public String clear(@RequestParam(required = false) Long projectId,
                        RedirectAttributes flash) {
        if (projectId != null) {
            changeLogRepository.deleteByProjectId(projectId);
        } else {
            changeLogRepository.deleteAllBulk();
        }
        flash.addFlashAttribute("message", "log.clear.success");
        return "redirect:/change-logs" + (projectId != null ? "?projectId=" + projectId : "");
    }
}
