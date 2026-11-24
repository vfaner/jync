package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.RowCountJobService;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.auth.AccessGuard;

/**
 * Exact row counts for one table, used by the detail page's on-demand check.
 *
 * <p>Read-only, but it does run a full COUNT(*) on both sides, so the route is admin-only
 * (see SecurityConfig) and the browser calls it one table at a time.
 */
@RestController
@RequestMapping("/api/projects")
public class RowCountApiController {

    private final TablePreviewService tablePreviewService;
    private final RowCountJobService rowCountJobService;
    private final ProjectService projectService;

    public RowCountApiController(TablePreviewService tablePreviewService,
                                 RowCountJobService rowCountJobService,
                                 ProjectService projectService) {
        this.tablePreviewService = tablePreviewService;
        this.rowCountJobService = rowCountJobService;
        this.projectService = projectService;
    }

    @GetMapping("/{id}/row-count")
    public ResponseEntity<Map<String, Object>> rowCount(@PathVariable Long id,
                                                        @RequestParam String table,
                                                        @RequestParam(defaultValue = "exact")
                                                        String mode) {
        projectService.requireEditable(id, AccessGuard.current());
        boolean exact = !"estimate".equalsIgnoreCase(mode);
        TableRowCount counts = tablePreviewService.rowCount(id, table, exact);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("rowCount", counts);
        return ResponseEntity.ok(body);
    }

    /**
     * Starts the exact COUNT(*) out of band. The browser polls the returned job instead of
     * holding a request open for the minutes a very large table needs.
     */
    @PostMapping("/{id}/row-count-job")
    public ResponseEntity<Map<String, Object>> startRowCountJob(@PathVariable Long id,
                                                                @RequestParam String table) {
        projectService.requireEditable(id, AccessGuard.current());
        String jobId = rowCountJobService.submit(id, table);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("jobId", jobId);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/row-count-job/{jobId}")
    public ResponseEntity<Map<String, Object>> rowCountJob(@PathVariable Long id,
                                                           @PathVariable String jobId) {
        projectService.requireEditable(id, AccessGuard.current());
        Map<String, Object> body = new LinkedHashMap<>();
        rowCountJobService.find(id, jobId).ifPresentOrElse(job -> {
            body.put("success", true);
            body.put("job", job);
        }, () -> {
            body.put("success", false);
            body.put("message", "error.rowcount.job.unknown");
        });
        return ResponseEntity.ok(body);
    }
}
