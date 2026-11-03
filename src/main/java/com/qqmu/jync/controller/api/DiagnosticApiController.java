package com.qqmu.jync.controller.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.diagnostics.DiagnosticBundleService;
import com.qqmu.jync.service.diagnostics.DiagnosticBundleService.DiagnosticBundle;
import com.qqmu.jync.service.diagnostics.DiagnosticBundleService.DiagnosticBundleTooLargeException;

/**
 * Downloads the support bundle assembled by {@link DiagnosticBundleService}.
 *
 * <p>POST-only and admin-only (the blanket {@code POST /** hasRole('ADMIN')} rule). The audit
 * row is written before the bytes leave: if the append-only trail cannot record the export,
 * the export does not happen.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticApiController {

    static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final DiagnosticBundleService bundleService;
    private final AdminAuditService auditService;

    public DiagnosticApiController(DiagnosticBundleService bundleService,
                                   AdminAuditService auditService) {
        this.bundleService = bundleService;
        this.auditService = auditService;
    }

    @PostMapping("/bundle")
    public ResponseEntity<?> bundle() {
        DiagnosticBundle bundle;
        try {
            bundle = bundleService.build();
        } catch (DiagnosticBundleTooLargeException ex) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }

        auditService.record(AuditAction.DIAGNOSTICS_EXPORT,
                "diagnostics bundle: " + bundle.entryCount() + " entries");

        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + bundle.filename() + "\"")
                .body(bundle.zip());
    }
}
