package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.config.I18nConfig;
import com.qqmu.jync.service.upgrade.UpgradeService;
import com.qqmu.jync.service.upgrade.UpgradeService.Status;

import lombok.extern.slf4j.Slf4j;

/**
 * REST endpoints behind the one-click upgrade dialog: start the download from the chosen
 * mirror, poll progress, and trigger the restart once the new jar is installed.
 *
 * <p>All three are admin-only: the POSTs by the blanket verb rule, the status poll via
 * {@code ADMIN_ONLY_GET} — a viewer is never shown the upgrade button, so the poller has no
 * business answering for one either. Refusals surface as {@link IllegalStateException} and
 * become the shared 409 body through {@code ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/upgrade")
@Slf4j
public class UpgradeApiController {

    private final UpgradeService upgradeService;
    private final I18nConfig.MessageSourceAccessorHolder messages;

    public UpgradeApiController(UpgradeService upgradeService,
                                I18nConfig.MessageSourceAccessorHolder messages) {
        this.upgradeService = upgradeService;
        this.messages = messages;
    }

    /** Begins download + verify + swap; progress arrives via {@link #status()} polling. */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(name = "source", defaultValue = "GITEE") String source) {
        upgradeService.start(source);
        return ResponseEntity.ok(ok("msg.upgrade.started"));
    }

    /**
     * One poll snapshot. {@code LinkedHashMap} rather than {@code Map.of} because
     * {@code message}/{@code targetVersion} are legitimately null while idle or downloading.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Status s = upgradeService.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("phase", s.phase().name());
        body.put("message", render(s.message()));
        body.put("targetVersion", s.targetVersion());
        body.put("source", s.source());
        body.put("bytesRead", s.bytesRead());
        body.put("bytesTotal", s.bytesTotal());
        return body;
    }

    /**
     * Renders a progress message in the poller's locale.
     *
     * <p>Here rather than where the message was produced because the download thread has no
     * request: it would have to guess a language, and the guess would be whatever the JVM
     * started with. Rendering at read time also means a poller who switches language mid-download
     * sees the rest of the progress in the new one.
     *
     * <p>Unlike the POST responses — whose {@code msg.upgrade.*} keys travel to the browser and
     * are translated by the frontend dictionary — this text is rendered verbatim by app.js, so
     * it has to leave the server already worded.
     */
    private String render(UpgradeService.Localized message) {
        return message == null ? null
                : messages.get(message.key(), LocaleContextHolder.getLocale(), message.args());
    }

    /** Launches the detached restart helper and schedules this JVM's exit. */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, Object>> restart() {
        upgradeService.restart();
        return ResponseEntity.ok(ok("msg.upgrade.restarting"));
    }

    private Map<String, Object> ok(String messageKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", messageKey);
        return body;
    }
}
