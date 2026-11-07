package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.MetadataDbTestForm;
import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.settings.MetadataDbTester;
import com.qqmu.jync.util.AuditOrigin;
import com.qqmu.jync.util.JdbcUrlSanitizer;

/**
 * Tests a candidate metadata database from the 系统配置 page.
 *
 * <p>The form is never saved: the response carries the environment block to paste instead. A
 * failed connection still answers 200 with {@code success=false} — the operator asked a question
 * and got an answer. Malformed input (wrong scheme, unknown driver, line breaks) is a 400,
 * because that is a programming error in the page, not a database problem.
 *
 * <p>The password is passed to the driver and returned in the environment block; it never
 * reaches a log line or the audit trail, and the audit records the URL with credentials masked.
 */
@RestController
public class MetadataDbTestApiController {

    private final MetadataDbTester tester;
    private final AdminAuditService audit;

    public MetadataDbTestApiController(MetadataDbTester tester, AdminAuditService audit) {
        this.tester = tester;
        this.audit = audit;
    }

    @PostMapping("/system-config/db-test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody MetadataDbTestForm form,
                                                    HttpServletRequest request) {
        MetadataDbTester.Result result;
        try {
            result = tester.test(form);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(body(false, e.getMessage(), "", "", ""));
        }

        String version = result.product().isBlank() && result.version().isBlank()
                ? "-"
                : (result.product() + " " + result.version()).trim();
        audit.record(AuditAction.SYSTEM_DB_TEST,
                AuditDetail.of("audit.detail.dbTest", AuditOrigin.of(request),
                        JdbcUrlSanitizer.sanitize(form.getUrl()),
                        version,
                        result.success() ? "OK" : "FAILED"));

        return ResponseEntity.ok(body(result.success(), result.message(),
                result.product(), result.version(), result.envBlock()));
    }

    private static Map<String, Object> body(boolean success, String message,
                                            String product, String version, String envBlock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("message", message);
        body.put("product", product);
        body.put("version", version);
        body.put("envBlock", envBlock);
        return body;
    }
}
