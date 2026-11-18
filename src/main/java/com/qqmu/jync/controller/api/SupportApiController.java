package com.qqmu.jync.controller.api;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.PreDestroy;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qqmu.jync.service.ai.AiProviderService;
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

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ai-support-stream");
        t.setDaemon(true);
        return t;
    });

    public SupportApiController(SupportAssistantService assistant, AiProviderService providers) {
        this.assistant = assistant;
        this.providers = providers;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody SupportChatRequest request) {
        if (!providers.isAssistAvailable() || providers.activeProvider().isEmpty()) {
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

    private SseEmitter oneShot(String messageKey) {
        SseEmitter emitter = new SseEmitter(1_000L);
        executor.execute(() -> {
            SseSink sink = new SseSink(emitter);
            sink.error(messageKey);
            sink.doneAndComplete();
        });
        return emitter;
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

        public void error(String messageKey) {
            send("error", Map.of("messageKey", messageKey));
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
