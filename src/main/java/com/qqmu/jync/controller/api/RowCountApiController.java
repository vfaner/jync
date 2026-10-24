package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.service.TablePreviewService;

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

    public RowCountApiController(TablePreviewService tablePreviewService) {
        this.tablePreviewService = tablePreviewService;
    }

    @GetMapping("/{id}/row-count")
    public ResponseEntity<Map<String, Object>> rowCount(@PathVariable Long id,
                                                        @RequestParam String table) {
        TableRowCount counts = tablePreviewService.rowCount(id, table);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("rowCount", counts);
        return ResponseEntity.ok(body);
    }
}
