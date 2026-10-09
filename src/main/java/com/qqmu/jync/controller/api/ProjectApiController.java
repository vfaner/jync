package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.task.SyncTaskRunner;

import lombok.extern.slf4j.Slf4j;

/** REST endpoints backing the project control buttons. */
@RestController
@RequestMapping("/api/projects")
@Slf4j
public class ProjectApiController {

    private final ProjectService projectService;

    public ProjectApiController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** Enables the project and begins polling. */
    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id) {
        projectService.start(id);
        return ResponseEntity.ok(ok("msg.sync.started"));
    }

    /** Disables the project and removes its schedule. */
    @PostMapping("/{id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable Long id) {
        projectService.stop(id);
        return ResponseEntity.ok(ok("msg.sync.stopped"));
    }

    /**
     * Runs one cycle immediately.
     *
     * <p>Contends for the same lock as the scheduler. A request that arrives mid-cycle is not
     * an error: it comes back as a queued rerun, which the runner serves once the in-flight
     * cycle releases the lock.
     */
    @PostMapping("/{id}/sync-now")
    public ResponseEntity<Map<String, Object>> syncNow(@PathVariable Long id) {
        SyncTaskRunner.Outcome outcome = projectService.syncNow(id);
        switch (outcome.kind()) {
            case QUEUED: {
                // Accepted, not executed: amber rather than green or red, and no reload —
                // the page would refresh while the cycle the click queued behind is running.
                Map<String, Object> body = ok("msg.sync.rerun.queued");
                body.put("toastKind", "warn");
                body.put("noReload", true);
                return ResponseEntity.ok(body);
            }
            case NO_PROJECT:
                return ResponseEntity.notFound().build();
            default:
                break;
        }
        SyncResult result = outcome.result().orElseGet(SyncResult::new);
        Map<String, Object> body = ok(result.isSuccess()
                ? "msg.sync.completed" : "msg.sync.completed.errors");
        body.put("summary", result.summary());
        body.put("structureChanges", result.getStructureChanges());
        body.put("rowsInserted", result.getRowsInserted());
        body.put("rowsUpdated", result.getRowsUpdated());
        body.put("rowsDeleted", result.getRowsDeleted());
        body.put("tablesProcessed", result.getTablesProcessed());
        body.put("durationMs", result.getDurationMs());
        body.put("errors", result.getErrors());
        body.put("skippedTables", result.getSkippedTables());
        body.put("success", result.isSuccess());
        return ResponseEntity.ok(body);
    }

    /** Clears all cursors and snapshots so the next run performs a full reload. */
    @PostMapping("/{id}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable Long id) {
        projectService.resetProgress(id);
        return ResponseEntity.ok(ok("msg.progress.reset"));
    }

    /**
     * Admin override: drops the project's sync lease whoever holds it.
     *
     * <p>POST endpoints are already ADMIN-only in the filter chain, so no extra role check
     * here. The action is audited in the change log by the service.
     */
    @PostMapping("/{id}/force-unlock")
    public ResponseEntity<Map<String, Object>> forceUnlock(@PathVariable Long id) {
        projectService.forceUnlock(id);
        Map<String, Object> body = ok("msg.lock.forced");
        // A forced unlock can stop a live cycle; reload so the badge and buttons reflect it.
        body.put("toastKind", "warn");
        return ResponseEntity.ok(body);
    }

    /** Columns of a source table that could serve as a cursor. */
    @GetMapping("/{id}/cursor-candidates")
    public ResponseEntity<Map<String, Object>> cursorCandidates(@PathVariable Long id,
                                                                @RequestParam String table) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("columns", projectService.listCursorCandidates(id, table));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> ok(String messageKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", messageKey);
        return body;
    }
}
