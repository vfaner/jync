package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.service.version.VersionService;
import com.qqmu.jync.service.version.VersionService.UpdateCheck;

/**
 * The settings page's click-triggered update check, used only to light the nav badge.
 *
 * <p>Read-only and available to any signed-in user: viewers can open the settings page, so
 * they may ask whether a newer release exists. The check is synchronous with per-mirror
 * timeouts — the badge request waits, not the page render — and a failure is deliberately
 * an ordinary 200 with {@code checked=false}: offline hosts must see nothing, not an error.
 */
@RestController
@RequestMapping("/api/version")
public class VersionApiController {

    private final VersionService versionService;

    public VersionApiController(VersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping("/check")
    public Map<String, Object> check() {
        UpdateCheck check = versionService.checkNow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("checked", check.checked());
        body.put("updateAvailable", check.updateAvailable());
        body.put("currentVersion", check.currentVersion());
        body.put("latestVersion", check.latestVersion());
        body.put("latestTag", check.latestTag());
        body.put("releaseUrl", check.releaseUrl());
        return body;
    }
}
