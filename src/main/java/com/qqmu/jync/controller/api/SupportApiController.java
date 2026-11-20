package com.qqmu.jync.controller.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.PreDestroy;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qqmu.jync.service.ai.AiProviderService;
import com.qqmu.jync.service.ai.support.SupportActionService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.ai.support.SupportChatRequest;
import com.qqmu.jync.service.ai.support.SupportSink;
import com.qqmu.jync.service.auth.AccessGuard;

import lombok.extern.slf4j.Slf4j;

/**
 * The floating support assistant's only endpoint.
 *
 * <p>Streams every round of the answer, so the first sentence appears while the model is still
 * writing and tool status can be shown between rounds. A separate two-thread pool keeps a slow
 * provider from occupying request threads or the conversion assistant's pool.
 */
@RestController
@RequestMapping("/api/support")
@Slf4j
public class SupportApiController {

    /** Outlives the 90-second internal budget; only a safety net. */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    private final SupportAssistantService assistant;
    private final AiProviderService providers;
    private final SupportActionService actions;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ai-support-stream");
        t.setDaemon(true);
        return t;
    });

    public SupportApiController(SupportAssistantService assistant, AiProviderService providers,
                                SupportActionService actions) {
        this.assistant = assistant;
        this.providers = providers;
        this.actions = actions;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody SupportChatRequest request) {
        // activeProvider() is empty when the deployment switch or the saved switch is off.
        if (providers.activeProvider().isEmpty()) {
            return oneShot("error.ai.disabled");
        }
        AccessGuard actor = AccessGuard.current();
        String username = actor.username();
        if (!assistant.tryStart(username)) {
            throw new SupportBusyException();
        }
        String language = LocaleContextHolder.getLocale().getLanguage();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        SseSink sink = new SseSink(emitter);
        emitter.onTimeout(() -> {
            assistant.finish(username);
            sink.doneAndComplete();
        });
        emitter.onError(e -> {
            assistant.finish(username);
            sink.doneAndComplete();
        });
        try {
            executor.execute(() -> {
                try {
                    assistant.ask(actor, language, request, sink);
                } catch (RuntimeException e) {
                    log.warn("Support stream failed: {}", e.toString());
                    sink.error("error.unexpected");
                } finally {
                    assistant.finish(username);
                    sink.doneAndComplete();
                }
            });
        } catch (RejectedExecutionException e) {
            assistant.finish(username);
            sink.error("error.unexpected");
            sink.doneAndComplete();
        }
        return emitter;
    }

    /** No model call behind it, so it is written before the handler returns (buffered by Spring). */
    private SseEmitter oneShot(String messageKey) {
        SseEmitter emitter = new SseEmitter(1_000L);
        SseSink sink = new SseSink(emitter);
        sink.error(messageKey);
        sink.doneAndComplete();
        return emitter;
    }

    /**
     * Redeems an assistant-prepared action after the human clicked its confirmation card.
     *
     * <p>The filter chain already admits only administrators and operators on a POST, and
     * {@link SupportActionService} re-applies the ownership rule through the ordinary service
     * methods. The status code tells the card what happened: 410 means the token expired or
     * was already used, 403 means the caller may not touch that resource now, and 409 means
     * the resource is in a state that refuses the action.
     */
    @PostMapping("/action/{token}")
    public ResponseEntity<Map<String, Object>> confirmAction(@PathVariable String token) {
        SupportActionService.Result result = actions.execute(token, AccessGuard.current());
        HttpStatus status = switch (result.outcome()) {
            case OK -> HttpStatus.OK;
            case EXPIRED -> HttpStatus.GONE;
            case DENIED -> HttpStatus.FORBIDDEN;
            case FAILED -> HttpStatus.CONFLICT;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.ok());
        body.put("message", result.messageKey());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(SupportBusyException.class)
    public ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(409).body(Map.of("messageKey", "error.support.busy"));
    }

    static final class SupportBusyException extends RuntimeException {
    }

    /** Maps sink calls to SSE events; every send failure is swallowed after a client disconnect. */
    static final class SseSink implements SupportSink {
        private final SseEmitter emitter;
        private volatile boolean done;

        SseSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public void delta(String text) {
            send("delta", Map.of("text", text));
        }

        public void tool(String name, String labelKey) {
            send("tool", Map.of("name", name, "labelKey", labelKey));
        }

        public void draft(DraftView draft) {
            send("draft", Map.of("token", draft.token(), "title", draft.title(),
                    "summary", draft.summary(), "fields", draft.fields()));
        }

        public void action(ActionView action) {
            send("action", Map.of("token", action.token(),
                    "titleKey", action.titleKey(),
                    "resourceKindKey", action.resourceKindKey(),
                    "resourceName", action.resourceName()));
        }

        public void error(String messageKey) {
            send("error", Map.of("messageKey", messageKey));
        }

        public void error(String messageKey, String detail) {
            if (detail == null || detail.isBlank()) {
                error(messageKey);
                return;
            }
            send("error", Map.of("messageKey", messageKey, "detail", detail));
        }

        public void done() {
            if (!done) {
                done = true;
                send("done", Map.of());
            }
        }

        void doneAndComplete() {
            done();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed by a timeout or the client.
            }
        }

        private void send(String event, Object data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | RuntimeException e) {
                // The browser closed the panel or tab: nothing left to tell.
            }
        }
    }
}
