package com.qqmu.jync.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.service.cdc.CdcNotHolderException;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcProbeResult;

import lombok.extern.slf4j.Slf4j;

/**
 * REST pair behind the CDC detail-page widgets (task book B7/C3): the on-demand readiness
 * probe and the one-click baseline rebuild.
 *
 * <p>Lives in {@code controller} rather than {@code controller.api} on purpose: the JSON
 * contract here is the task-book one — {@code {error:"<message key>"}} on 400 (409 for the
 * cross-node holder refusal), {@code {ok:true}} on success — not the api package's
 * {@code {success,message}} body that {@code ApiExceptionHandler} (basePackages =
 * controller.api) would otherwise produce. The controller catches
 * {@link IllegalArgumentException} and {@link CdcNotHolderException} itself and shapes the
 * error body.
 *
 * <p>Authorization follows the existing filter-chain conventions: POST under /api/** is
 * ADMIN-only with CSRF, so no extra role check here (same as ProjectApiController).
 */
@RestController
@RequestMapping("/api/projects")
@Slf4j
public class CdcApiController {

    /**
     * The fixed wording BinlogCdcProbe.checkServerId puts in a colliding SERVER_ID detail
     * ("source server_id N collides with the id Jync would present (N)"). The check can
     * also fail for a zero id or an unreadable variable — those are not conflicts, so the
     * conflict flag is derived from the failure PLUS this marker, per the Task 16 test
     * contract ("该项失败且 detail 指出与计划 id 相撞").
     */
    private static final String SERVER_ID_COLLISION_MARKER = "collides";

    private final CdcOperationsService operations;

    public CdcApiController(CdcOperationsService operations) {
        this.operations = operations;
    }

    /**
     * Itemized B2 capability probe against the project's real source, with the planned
     * server id checked for collision (B7: know before the consumer ever connects).
     * Never throws for an unreachable source — that degrades to a failed VERSION check.
     */
    @GetMapping("/{id}/cdc-probe")
    public ResponseEntity<Map<String, Object>> probe(@PathVariable Long id) {
        CdcProbeResult result;
        try {
            result = operations.probeProject(id);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("passed", result.passed());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (CdcProbeResult.Check check : result.getChecks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", check.getName());
            item.put("passed", check.isPassed());
            item.put("detail", check.getDetail());
            checks.add(item);
        }
        body.put("checks", checks);
        body.put("serverIdConflict", hasServerIdConflict(result));
        return ResponseEntity.ok(body);
    }

    /**
     * One-click recovery (B7): stop → drop position → clear cursors → lift the ERROR hat
     * → restart with a fresh full load. The success body carries {@code ok:true} per the
     * task-book contract, plus the {@code success}/{@code message} fields the shared
     * api-btn handler in app.js toasts and reloads on — one POST, both contracts served.
     *
     * <p>A cross-node refusal (final review FIX 1) becomes 409 rather than 400: the request
     * itself is well-formed, it merely conflicts with the lease another live node holds.
     * The service throws BEFORE any mutation, so a rejected rebaseline writes no trail rows.
     */
    @PostMapping("/{id}/cdc-rebaseline")
    public ResponseEntity<Map<String, Object>> rebaseline(@PathVariable Long id) {
        boolean restarted;
        try {
            restarted = operations.rebaseline(id);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        } catch (CdcNotHolderException e) {
            return conflict(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("success", true);
        // A disabled task is reset, not restarted: say so instead of the running-task
        // "rebuilt" toast that would imply a baseline is loading right now.
        body.put("message", restarted ? "msg.cdc.rebaseline.done"
                : "msg.cdc.rebaseline.pending");
        return ResponseEntity.ok(body);
    }

    private static boolean hasServerIdConflict(CdcProbeResult result) {
        for (CdcProbeResult.Check check : result.getChecks()) {
            if (CdcProbeResult.SERVER_ID.equals(check.getName()) && !check.isPassed()
                    && check.getDetail() != null
                    && check.getDetail().contains(SERVER_ID_COLLISION_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", String.valueOf(e.getMessage()));
        // Same dual shape as the success body: {error} per contract, {success,message} so
        // the shared api-btn toast can localize the refusal without any new JS branch.
        body.put("success", false);
        body.put("message", String.valueOf(e.getMessage()));
        return ResponseEntity.badRequest().body(body);
    }

    /** 409 twin of {@link #badRequest}: same dual body shape, conflict status (FIX 1). */
    private static ResponseEntity<Map<String, Object>> conflict(CdcNotHolderException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", String.valueOf(e.getMessage()));
        body.put("success", false);
        body.put("message", String.valueOf(e.getMessage()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
