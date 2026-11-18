package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.sync.ContentAuditRunner;

/**
 * On-demand content audit for one table, behind the detail page's button.
 *
 * <p>POST because it opens both configured database connections and reads row values; the
 * filter chain admits the two write roles, and the project's owner is checked here, before
 * either connection is opened. A SKIPPED or ERROR audit is still an HTTP 200 — the audit
 * status describes the table, not the request.
 */
@RestController
@RequestMapping("/api/projects")
public class ContentAuditApiController {

    private final ContentAuditRunner contentAuditRunner;
    private final ProjectService projectService;

    public ContentAuditApiController(ContentAuditRunner contentAuditRunner,
                                     ProjectService projectService) {
        this.contentAuditRunner = contentAuditRunner;
        this.projectService = projectService;
    }

    @PostMapping("/{id}/content-audit")
    public ResponseEntity<Map<String, Object>> audit(@PathVariable Long id,
                                                     @RequestParam String table) {
        projectService.requireEditable(id, AccessGuard.current());
        TableContentAudit result = contentAuditRunner.auditProject(id, table);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("contentAudit", result);
        return ResponseEntity.ok(body);
    }
}
