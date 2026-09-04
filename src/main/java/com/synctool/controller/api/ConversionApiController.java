package com.synctool.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.synctool.service.ai.AiSqlAssistant;
import com.synctool.service.ai.CandidateValidator;
import com.synctool.service.ai.ConversionAssistService;

import lombok.extern.slf4j.Slf4j;

/**
 * Async endpoints for the conversion review page.
 *
 * <p>Separate from the controller because the AI draft call can take seconds and must not block
 * a form POST. The page calls these via fetch(), and the response body is rendered by JS or
 * shown in a toast, not by a redirect.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/conversions")
@Slf4j
public class ConversionApiController {

    private final ConversionAssistService assistService;

    public ConversionApiController(ConversionAssistService assistService) {
        this.assistService = assistService;
    }

    /**
     * Asks the AI to draft a candidate for the given object.
     *
     * <p>The response contains the SQL and the uncertainty list so the page can fill the editor
     * and show the caveats that the reviewer needs to check.
     *
     * <p>Always answers 200 with a {@code success} flag. The caller is a {@code fetch()} that
     * renders {@code message} into a toast, so a 500 carrying Spring's HTML error page would
     * surface to the user as an unexplained failure.
     */
    @PostMapping("/{kind}/{name:.+}/draft")
    public ResponseEntity<?> draft(@PathVariable Long projectId, @PathVariable String kind,
                                   @PathVariable String name) {
        try {
            AiSqlAssistant.Candidate candidate = assistService.draft(projectId, kind, name);
            if (!candidate.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", nullToEmpty(candidate.getMessage()),
                        "uncertainties", candidate.getUncertainties(),
                        "model", nullToEmpty(candidate.getModel()),
                        "elapsedMs", candidate.getElapsedMs()));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sql", candidate.getSql(),
                    "uncertainties", candidate.getUncertainties(),
                    "model", nullToEmpty(candidate.getModel()),
                    "elapsedMs", candidate.getElapsedMs()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(e);
        }
    }

    /**
     * Syntax-checks a candidate against the real target.
     *
     * <p>The page sends the current editor content so the check covers any hand edits too.
     * The object is created under a throwaway name and dropped.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validate(@PathVariable Long projectId,
                                      @RequestBody Map<String, String> body) {
        String name = body.get("name");
        String sql = body.get("sql");
        String kind = body.get("kind");

        if (name == null || sql == null || kind == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Missing parameters"));
        }

        try {
            CandidateValidator.ValidationResult result = assistService.validate(projectId, name, sql);
            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "message", result.getMessage() == null ? "OK" : result.getMessage(),
                    "tempName", nullToEmpty(result.getTempName()),
                    "caveats", result.getCaveats()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(e);
        }
    }

    /**
     * Reports a rejected request as a normal response body.
     *
     * <p>The message is a bundle key the page resolves, so an exception with no message would
     * leave the toast blank rather than merely unhelpful.
     */
    private ResponseEntity<?> failure(RuntimeException e) {
        log.debug("Conversion request rejected: {}", e.getMessage());
        return ResponseEntity.ok(Map.of(
                "success", false,
                "message", e.getMessage() == null ? "error.unexpected" : e.getMessage()));
    }

    /** {@code Map.of} rejects null values with an NPE, which would become a 500. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}