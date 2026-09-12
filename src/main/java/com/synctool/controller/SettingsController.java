package com.synctool.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.synctool.config.SyncProperties;
import com.synctool.model.DatabaseType;
import com.synctool.service.task.SyncLockService;
import com.synctool.service.task.SyncScheduler;
import com.synctool.service.version.VersionService;

/** Read-only view of effective global settings and runtime state. */
@Controller
public class SettingsController {

    private final SyncProperties properties;
    private final SyncScheduler scheduler;
    private final SyncLockService lockService;
    private final VersionService versionService;

    public SettingsController(SyncProperties properties, SyncScheduler scheduler,
                              SyncLockService lockService, VersionService versionService) {
        this.properties = properties;
        this.scheduler = scheduler;
        this.lockService = lockService;
        this.versionService = versionService;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("properties", properties);
        model.addAttribute("supportedTypes", DatabaseType.values());
        model.addAttribute("scheduledProjectIds", scheduler.scheduledProjectIds());
        model.addAttribute("instanceId", lockService.getOwnerId());
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("versionInfo", versionService.snapshot());
        model.addAttribute("activeNav", "settings");
        return "settings";
    }
}
