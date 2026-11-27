package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.TableMaintenanceService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.task.SyncTaskRunner;

/**
 * Per-table reload behind the detail page's row action.
 *
 * <p>POST because it writes rows. The security chain admits the two write roles; project
 * ownership is checked here before the service runs.
 */
@RestController
@RequestMapping("/api/projects")
public class TableMaintenanceApiController {

    private final TableMaintenanceService tableMaintenanceService;
    private final ProjectService projectService;

    public TableMaintenanceApiController(TableMaintenanceService tableMaintenanceService,
                                         ProjectService projectService) {
        this.tableMaintenanceService = tableMaintenanceService;
        this.projectService = projectService;
    }

    /**
     * Reloads one table on a stopped project. A running project is refused: its polling cycle
     * or CDC consumer would write the same rows while this reload runs. The CDC position is
     * not moved, so replay continues from the same point after the project is restarted.
     */
    @PostMapping("/{id}/table-reload")
    public ResponseEntity<Map<String, Object>> reload(@PathVariable Long id,
                                                      @RequestParam String table) {
        projectService.requireEditable(id, AccessGuard.current());
        try {
            SyncTaskRunner.Outcome outcome = tableMaintenanceService.reload(id, table);
            switch (outcome.kind()) {
                case BUSY:
                    return ResponseEntity.ok(failure("error.table.reload.busy"));
                case NO_PROJECT:
                    return ResponseEntity.notFound().build();
                default:
                    break;
            }
            var result = outcome.result().orElse(null);
            if (result == null) {
                return ResponseEntity.ok(failure("error.table.reload.failed"));
            }
            if (result.isDeferred()) {
                // Nothing was executed: the connection pool was at its cycle ceiling.
                return ResponseEntity.ok(failure("error.table.reload.busy"));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", result.isSuccess());
            body.put("message", result.isSuccess()
                    ? "msg.table.reload.done" : "error.table.reload.failed");
            body.put("summary", result.summary());
            body.put("errors", result.getErrors());
            body.put("rowsInserted", result.getRowsInserted());
            body.put("rowsUpdated", result.getRowsUpdated());
            body.put("rowsDeleted", result.getRowsDeleted());
            body.put("durationMs", result.getDurationMs());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(failure(e.getMessage()));
        }
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message == null ? "error.unexpected" : message);
        return body;
    }
}
