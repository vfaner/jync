package com.qqmu.jync.controller;

import java.util.List;

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
public class SystemLogController {

    private final SystemLogService logService;

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
        return "system-logs";
    }
}
