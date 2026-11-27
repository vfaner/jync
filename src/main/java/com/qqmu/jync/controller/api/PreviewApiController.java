package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.dto.TablePreviewCheck;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.TablePreviewService.Side;
import com.qqmu.jync.service.auth.AccessGuard;

/**
 * Read-only preview of one synced table.
 *
 * <p>Separate from {@link ProjectApiController} because that class is mocked whole in its
 * own slice test; adding an unrelated dependency there would leak into it. The path still
 * lives under the project's API namespace.
 */
@RestController
@RequestMapping("/api/projects")
public class PreviewApiController {

    private final TablePreviewService tablePreviewService;
    private final ProjectService projectService;

    public PreviewApiController(TablePreviewService tablePreviewService,
                                ProjectService projectService) {
        this.tablePreviewService = tablePreviewService;
        this.projectService = projectService;
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id,
                                                       @RequestParam String table,
                                                       @RequestParam(defaultValue = "source") String side) {
        projectService.requireEditable(id, AccessGuard.current());
        TablePreview preview = tablePreviewService.preview(id, table, parseSide(side));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("preview", preview);
        return ResponseEntity.ok(body);
    }

    /** First-ten-rows / column-count check behind the preview modal's third tab. */
    @PostMapping("/{id}/preview-check")
    public ResponseEntity<Map<String, Object>> check(@PathVariable Long id,
                                                     @RequestParam String table,
                                                     @RequestParam(defaultValue = "50")
                                                     int rows) {
        projectService.requireEditable(id, AccessGuard.current());
        TablePreviewCheck check = tablePreviewService.checkPreview(id, table, rows);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("previewCheck", check);
        return ResponseEntity.ok(body);
    }

    /** An unrecognized side is a bad request, not a server error: the caller sent it. */
    private static Side parseSide(String raw) {
        try {
            return Side.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("error.preview.side.invalid");
        }
    }
}
