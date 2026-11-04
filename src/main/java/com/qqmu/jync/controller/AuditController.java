package com.qqmu.jync.controller;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.RequestContextUtils;

import com.qqmu.jync.dto.Pager;
import com.qqmu.jync.model.AuditEvent;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetailRenderer;

/**
 * Paged, read-only view of the admin audit trail.
 *
 * <p>Admin-only (see {@code SecurityConfig.ADMIN_ONLY_GET}) and there is no POST anywhere in
 * this controller: the trail cannot be cleared, filtered away, or edited from the UI.
 */
@Controller
public class AuditController {

    private final AdminAuditService auditService;
    private final AuditDetailRenderer detailRenderer;

    public AuditController(AdminAuditService auditService,
                           AuditDetailRenderer detailRenderer) {
        this.auditService = auditService;
        this.detailRenderer = detailRenderer;
    }

    @GetMapping("/audits")
    public String list(@RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(required = false) Integer spanL,
                       @RequestParam(required = false) Integer spanR,
                       HttpServletRequest request,
                       Model model) {
        Pager pager = Pager.of(page, size, auditService.count(), spanL, spanR);
        Page<AuditEvent> events = auditService.page(pager.getPage() - 1, pager.getSize());
        Locale locale = RequestContextUtils.getLocale(request);
        Map<Long, String> details = new LinkedHashMap<>();
        for (AuditEvent event : events.getContent()) {
            details.put(event.getId(), detailRenderer.render(event.getDetail(), locale));
        }
        model.addAttribute("events", events);
        model.addAttribute("details", details);
        model.addAttribute("pager", pager);
        return "audits";
    }
}
