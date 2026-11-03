package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.service.sync.SyncPrecheckService;

/**
 * A read-only rehearsal of the next sync cycle, used by the detail page.
 *
 * <p>It opens both configured connections and reads metadata, so the route is admin-only
 * (see SecurityConfig). Findings are the response body, not an HTTP error: a report with
 * ERROR checks is still a successful precheck.
 */
@RestController
@RequestMapping("/api/projects")
public class PrecheckApiController {

    private final SyncPrecheckService precheckService;

    public PrecheckApiController(SyncPrecheckService precheckService) {
        this.precheckService = precheckService;
    }

    @GetMapping("/{id}/precheck")
    public ResponseEntity<Map<String, Object>> precheck(@PathVariable Long id) {
        PrecheckReport report = precheckService.precheck(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("precheck", report);
        return ResponseEntity.ok(body);
    }
}
