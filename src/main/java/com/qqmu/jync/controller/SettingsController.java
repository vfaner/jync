package com.qqmu.jync.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionService;

/** Read-only view of effective global settings and runtime state. */
@Controller
public class SettingsController {

    private final SyncProperties properties;
    private final SyncScheduler scheduler;
    private final SyncLockService lockService;
    private final VersionService versionService;
    private final String giteeUrl;
    private final String siteUrl;
    private final List<String> contactQqs;
    private final String contactQqGroup;
    private final String contactEmail;
    private final String contactWechat;

    public SettingsController(SyncProperties properties, SyncScheduler scheduler,
                              SyncLockService lockService, VersionService versionService,
                              @Value("${app.gitee-url:}") String giteeUrl,
                              @Value("${app.site-url:}") String siteUrl,
                              @Value("${app.contact-qq:}") String contactQq,
                              @Value("${app.contact-qq-group:}") String contactQqGroup,
                              @Value("${app.contact-email:}") String contactEmail,
                              @Value("${app.contact-wechat:}") String contactWechat) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.lockService = lockService;
        this.versionService = versionService;
        this.giteeUrl = giteeUrl;
        this.siteUrl = siteUrl;
        this.contactQqs = splitHandles(contactQq);
        this.contactQqGroup = contactQqGroup;
        this.contactEmail = contactEmail;
        this.contactWechat = contactWechat;
    }

    /** One config value may carry several handles: "817094/2912167928" or a comma/space list. */
    private static List<String> splitHandles(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[/,;\\s]+"))
                .map(String::trim)
                .filter(handle -> !handle.isEmpty())
                .toList();
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("properties", properties);
        model.addAttribute("supportedTypes", DatabaseType.values());
        model.addAttribute("scheduledProjectIds", scheduler.scheduledProjectIds());
        model.addAttribute("instanceId", lockService.getOwnerId());
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("versionInfo", versionService.snapshot());
        model.addAttribute("giteeUrl", giteeUrl);
        model.addAttribute("siteUrl", siteUrl);
        model.addAttribute("contactQqs", contactQqs);
        model.addAttribute("contactQqGroup", contactQqGroup);
        model.addAttribute("contactEmail", contactEmail);
        model.addAttribute("contactWechat", contactWechat);
        return "settings";
    }
}
