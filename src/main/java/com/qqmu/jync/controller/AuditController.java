package com.qqmu.jync.controller;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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
 * Paged view of the admin audit trail, plus the administrator's date-based purge.
 *
 * <p>Admin-only (see {@code SecurityConfig.ADMIN_ONLY_GET} and {@code ADMIN_ONLY_POST}). The
 * trail cannot be edited or filtered away; the single write is {@link #purge}, which removes
 * rows on or before a chosen day, always keeps today, and records itself as a new audit event.
 */
@Controller
@lombok.extern.slf4j.Slf4j
public class AuditController {

    private final AdminAuditService auditService;
    private final AuditDetailRenderer detailRenderer;

    /** Optional so @WebMvcTest slices that omit the message source still wire the controller. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.MessageSource messageSource;

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
        // The purge picker may not offer today: today's rows always survive a date purge.
        model.addAttribute("purgeMaxDate",
                com.qqmu.jync.util.LogPurgeDates.maxSelectable().toString());
        return "audits";
    }

    /**
     * Removes audit events on or before the selected day (inclusive) and answers JSON with
     * the localized count. The purge itself is recorded by the service, so the trim leaves a
     * trace even though the rows it removed are gone.
     */
    @org.springframework.web.bind.annotation.PostMapping("/audits/purge")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> purge(@org.springframework.web.bind.annotation.RequestParam("date")
                                     String date) {
        java.time.LocalDate day;
        try {
            day = java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            return Map.of("success", false, "message", text("log.purge.date.invalid"));
        }
        try {
            int removed = auditService.purgeBefore(day);
            return Map.of("success", true, "message", text("log.purge.success", removed));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Audit purge failed: {}", e.toString());
            return Map.of("success", false, "message", text("error.unexpected"));
        }
    }

    private String text(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, args,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale());
        } catch (org.springframework.context.NoSuchMessageException ignored) {
            return key;
        }
    }
}
