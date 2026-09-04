package com.synctool.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.synctool.model.AiProtocol;
import com.synctool.model.AiProvider;
import com.synctool.service.ai.AiConnectionTestService;
import com.synctool.service.ai.AiProviderService;

/** REST endpoints for probing AI endpoints and prefilling the form. */
@RestController
@RequestMapping("/api/ai")
public class AiApiController {

    private final AiProviderService service;

    public AiApiController(AiProviderService service) {
        this.service = service;
    }

    /** Probes a saved provider and records the outcome. */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        return ResponseEntity.ok(describe(service.test(id)));
    }

    /**
     * Probes settings that have not been saved yet.
     *
     * <p>A blank key falls back to the stored one, so an existing provider can be re-probed
     * from the edit form without the secret being sent to the browser first.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testTransient(
            @ModelAttribute AiProvider provider,
            @RequestParam(required = false) String rawApiKey) {
        return ResponseEntity.ok(describe(service.testTransient(provider, rawApiKey)));
    }

    /** Default base URL for a protocol, to prefill the form when the selection changes. */
    @GetMapping("/protocol-defaults")
    public ResponseEntity<Map<String, Object>> protocolDefaults(@RequestParam String protocol) {
        AiProtocol p = AiProtocol.fromName(protocol);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("protocol", p.name());
        body.put("displayName", p.getDisplayName());
        body.put("defaultBaseUrl", p.getDefaultBaseUrl());
        body.put("chatPath", p.getChatPath());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> describe(AiConnectionTestService.TestResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.isSuccess());
        body.put("message", result.getMessage());
        body.put("model", result.getModel());
        body.put("endpoint", result.getEndpoint());
        body.put("elapsedMs", result.getElapsedMs());
        return body;
    }
}
