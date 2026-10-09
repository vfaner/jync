package com.qqmu.jync.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.service.AdminAuditService;

/**
 * Paged, read-only view of the admin audit trail.
 *
 * <p>Admin-only (see {@code SecurityConfig.ADMIN_ONLY_GET}) and there is no POST anywhere in
 * this controller: the trail cannot be cleared, filtered away, or edited from the UI.
 */
@Controller
public class AuditController {

    private final AdminAuditService auditService;

    public AuditController(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audits")
    public String list(@RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer spanL,
                       @RequestParam(required = false) Integer spanR,
                       Model model) {
        Pager pager = Pager.of(page, size, auditService.count(), spanL, spanR);
        Page<AuditEvent> events = auditService.page(pager.getPage() - 1, pager.getSize());
        model.addAttribute("events", events);
        model.addAttribute("pager", pager);
        return "audits";
    }
}
