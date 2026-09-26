package com.qqmu.jync.controller.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qqmu.jync.service.ai.AiSqlAssistant;
import com.qqmu.jync.service.ai.CandidateValidator;
import com.qqmu.jync.service.ai.ConversionAssistService;

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

    /**
     * Generous ceiling for one SSE draft. The provider's own timeout bounds the model call;
     * this only exists so an emitter can never outlive a forgotten request.
     */
    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ConversionAssistService assistService;

    /** Small daemon pool: drafts are a one-reviewer-at-a-time workflow, not a throughput path. */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-draft-stream");
        t.setDaemon(true);
        return t;
    });

    public ConversionApiController(ConversionAssistService assistService) {
        this.assistService = assistService;
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
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
            return ResponseEntity.ok(payload(assistService.draft(projectId, kind, name)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return failure(e);
        }
    }

    /**
     * Streaming twin of {@link #draft}: the candidate arrives as SSE {@code delta} events while
     * the model writes it, and a final {@code done} event carries the same payload {@link #draft}
     * returns (parsed SQL, uncertainties, model, elapsed). The page fills the editor live from
     * the deltas and replaces it with the parsed SQL on {@code done}.
     *
     * <p>Runs on a dedicated pool because the servlet thread must not be occupied for the whole
     * generation, and the emitter timeout outlives any sane provider timeout.
     */
    @PostMapping(value = "/{kind}/{name:.+}/draft/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter draftStream(@PathVariable Long projectId, @PathVariable String kind,
                                  @PathVariable String name) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        executor.execute(() -> {
            Map<String, Object> payload = null;
            try {
                payload = payload(assistService.draftStream(projectId, kind, name,
                        delta -> sendDelta(emitter, delta)));
            } catch (IllegalArgumentException | IllegalStateException e) {
                payload = Map.of(
                        "success", false,
                        "message", e.getMessage() == null ? "error.unexpected" : e.getMessage(),
                        "uncertainties", List.of(),
                        "model", "",
                        "elapsedMs", 0,
                        "cached", false);
            } catch (RuntimeException e) {
                // The sink aborts the model call once the client is gone; there is then nobody
                // left to deliver a done event to, so nothing is sent.
                log.debug("Draft stream aborted: {}", e.toString());
            }
            if (payload != null) {
                try {
                    emitter.send(SseEmitter.event().name("done")
                            .data(payload, MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException | IllegalStateException e) {
                    log.debug("Draft stream client vanished before done: {}", e.toString());
                }
            }
        });
        return emitter;
    }

    private void sendDelta(SseEmitter emitter, String delta) {
        try {
            emitter.send(SseEmitter.event().name("delta")
                    .data(Map.of("text", delta), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client gone: unwinding through the model call is the only honest exit.
            throw new IllegalStateException("draft stream aborted", e);
        }
    }

    /** One shape for both draft endpoints, so the page needs a single renderer. */
    private Map<String, Object> payload(AiSqlAssistant.Candidate candidate) {
        if (!candidate.isSuccess()) {
            return Map.of(
                    "success", false,
                    "message", nullToEmpty(candidate.getMessage()),
                    "uncertainties", candidate.getUncertainties(),
                    "model", nullToEmpty(candidate.getModel()),
                    "elapsedMs", candidate.getElapsedMs(),
                    "cached", candidate.isCached());
        }
        return Map.of(
                "success", true,
                "sql", candidate.getSql(),
                "uncertainties", candidate.getUncertainties(),
                "model", nullToEmpty(candidate.getModel()),
                "elapsedMs", candidate.getElapsedMs(),
                "cached", candidate.isCached());
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
            String message = result.getMessage();
            if (message == null) {
                // A SQLException with no message must not make a failed check read as "OK";
                // only a success may default to it, a failure falls back to a bundle key.
                message = result.isSuccess() ? "OK" : "error.validate.unknown";
            }
            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "message", message,
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