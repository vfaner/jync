package com.qqmu.jync.controller;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
    private final long reconcileIntervalMs;

    public SettingsController(SyncProperties properties, SyncScheduler scheduler,
                              SyncLockService lockService, VersionService versionService,
                              @Value("${app.gitee-url:}") String giteeUrl,
                              @Value("${app.site-url:}") String siteUrl,
                              @Value("${app.contact-qq:}") String contactQq,
                              @Value("${app.contact-qq-group:}") String contactQqGroup,
                              @Value("${app.contact-email:}") String contactEmail,
                              @Value("${app.contact-wechat:}") String contactWechat,
                              @Value("${sync.reconcile-interval-ms:30000}")
                              long reconcileIntervalMs) {
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
        this.reconcileIntervalMs = reconcileIntervalMs;
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
        model.addAttribute("reconcileIntervalMs", reconcileIntervalMs);
        model.addAttribute("supportedTypes", DatabaseType.values());
        model.addAttribute("scheduledProjectIds", scheduler.scheduledProjectIds());
        model.addAttribute("instanceId", lockService.getOwnerId());
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("osName", osName());
        model.addAttribute("startedAt", startedAt());
        model.addAttribute("versionInfo", versionService.snapshot());
        model.addAttribute("giteeUrl", giteeUrl);
        model.addAttribute("siteUrl", siteUrl);
        model.addAttribute("contactQqs", contactQqs);
        model.addAttribute("contactQqGroup", contactQqGroup);
        model.addAttribute("contactEmail", contactEmail);
        model.addAttribute("contactWechat", contactWechat);
        return "settings";
    }

    /** "Linux 6.8.0-45-generic (aarch64)" — the machine this page describes. */
    private static String osName() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    /**
     * When this JVM started, in the server's own zone.
     *
     * <p>Preformatted rather than handed to {@code #temporals}: a timestamp is a fact about
     * this machine, and it reads the same in both interface languages.
     */
    private static String startedAt() {
        long millis = ManagementFactory.getRuntimeMXBean().getStartTime();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));
    }
}
