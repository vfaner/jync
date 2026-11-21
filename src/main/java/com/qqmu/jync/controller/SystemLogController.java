package com.qqmu.jync.controller;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.qqmu.jync.service.log.SystemLogService;

/**
 * The system-log page: pick a file, a level and a keyword, read the matching tail.
 *
 * <p>Reading and escaping live in {@link SystemLogService}; this class only turns the three
 * query parameters into model attributes, normalizing anything unrecognized back to the
 * defaults instead of failing the request.
 */
@Controller
@lombok.extern.slf4j.Slf4j
public class SystemLogController {

    private final SystemLogService logService;

    /** Optional so @WebMvcTest slices that omit the message source still wire the controller. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.MessageSource messageSource;

    public SystemLogController(SystemLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/system-logs")
    public String page(@RequestParam(required = false) String file,
                       @RequestParam(required = false) String level,
                       @RequestParam(required = false) String q,
                       Model model) {
        List<SystemLogService.LogFile> files = logService.listFiles();
        String selectedFile = StringUtils.hasText(file)
                ? file.trim()
                : (files.isEmpty() ? null : files.get(0).getName());
        String selectedLevel = SystemLogService.normalizeLevel(level);

        model.addAttribute("files", files);
        model.addAttribute("selectedFile", selectedFile);
        model.addAttribute("level", selectedLevel);
        model.addAttribute("levels", SystemLogService.LEVELS);
        // The keyword is echoed back into the box; the service only ever sees a trimmed copy.
        model.addAttribute("keyword", q == null ? "" : q);
        model.addAttribute("result", logService.query(selectedFile, selectedLevel, q));
        // The purge picker may not offer today: today's rows always survive a date purge.
        model.addAttribute("purgeMaxDate",
                com.qqmu.jync.util.LogPurgeDates.maxSelectable().toString());
        return "system-logs";
    }

    /**
     * Deletes rolled log archives dated on or before the selected day. The live file is never
     * touched; retention (logging.logback.rollingpolicy.max-history) prunes automatically as
     * a backstop. Answers JSON so the shared purge modal can toast the localized count.
     */
    @org.springframework.web.bind.annotation.PostMapping("/system-logs/purge")
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
            int removed = logService.purgeBefore(day);
            return Map.of("success", true, "message", text("log.purge.success", removed));
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "message", text(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("System-log purge failed: {}", e.toString());
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
