package com.qqmu.jync.controller.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.connection.MigrationConnectionTestResult;
import com.qqmu.jync.service.migration.connection.MigrationDiscoveryItem;

/**
 * JSON helpers for the dedicated-migration forms: index discovery and connection probes.
 *
 * <p>Failures answer 200 with {@code success:false} so the shared front-end renderer can show
 * the translated reason instead of a generic HTTP error.
 */
@RestController
@RequestMapping("/api/migrations")
public class MigrationApiController {

    private final MigrationConnectionService service;

    public MigrationApiController(MigrationConnectionService service) {
        this.service = service;
    }

    @GetMapping("/indices")
    public Map<String, Object> indices(@RequestParam Long connectionId,
                                       @RequestParam(defaultValue = "") String pattern) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            List<MigrationDiscoveryItem> indices =
                    service.listIndices(connectionId, pattern);
            body.put("success", true);
            body.put("count", indices.size());
            body.put("indices", indices);
        } catch (RuntimeException e) {
            body.put("success", false);
            body.put("message", e.getMessage());
        }
        return body;
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testTransient(@ModelAttribute MigrationConnectionForm form,
                                             @RequestParam(required = false) String rawPassword,
                                             @RequestParam(required = false) String rawApiKey) {
        try {
            return result(service.testTransient(form, rawPassword, rawApiKey),
                    form.getBaseUrl());
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    @PostMapping("/connections/{id}/test")
    public Map<String, Object> testSaved(@PathVariable Long id) {
        try {
            return result(service.test(id), null);
        } catch (RuntimeException e) {
            return failure(e.getMessage());
        }
    }

    private static Map<String, Object> result(MigrationConnectionTestResult result,
                                              String endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("message", result.message());
        body.put("product", result.product());
        body.put("version", result.version());
        body.put("clusterName", result.clusterName());
        body.put("elapsedMs", result.elapsedMs());
        if (endpoint != null) {
            body.put("endpoint", endpoint);
        }
        if (result.success()) {
            // The shared connection-test renderer reads productInfo; keep one line that
            // carries product, version and cluster without changing app.js.
            StringBuilder info = new StringBuilder(
                    result.product() == null ? "search" : result.product());
            if (result.version() != null) {
                info.append(' ').append(result.version());
            }
            if (result.clusterName() != null) {
                info.append(" · ").append(result.clusterName());
            }
            body.put("productInfo", info.toString());
        }
        return body;
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
