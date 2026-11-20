package com.qqmu.jync.controller.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.qqmu.jync.service.ai.AiProviderService;
import com.qqmu.jync.service.ai.support.SupportActionService;
import com.qqmu.jync.service.ai.support.SupportAssistantService;
import com.qqmu.jync.service.ai.support.SupportChatRequest;
import com.qqmu.jync.service.ai.support.SupportRunStore;
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

    /** The page keeps this id to reattach after a navigation destroyed its fetch. */
    static final String RUN_ID_HEADER = "X-Support-Run-Id";

    private final SupportAssistantService assistant;
    private final AiProviderService providers;
    private final SupportActionService actions;
    private final SupportRunStore runs;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ai-support-stream");
        t.setDaemon(true);
        return t;
    });

    public SupportApiController(SupportAssistantService assistant, AiProviderService providers,
                                SupportActionService actions, SupportRunStore runs) {
        this.assistant = assistant;
        this.providers = providers;
        this.actions = actions;
        this.runs = runs;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody SupportChatRequest request, HttpServletResponse response) {
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
        // The browser reads this header before the stream ends, so a page that navigates away
        // can come back and pick the answer up from the run buffer.
        String runId = runs.create(username);
        response.setHeader(RUN_ID_HEADER, runId);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        SseSink sink = new SseSink(emitter, runs, runId);
        // A closed stream is not a finished answer: the worker keeps running and the single-
        // flight lock stays held until it actually finishes, or the user reattaches and the
        // page sees its own run still in progress.
        emitter.onTimeout(sink::detach);
        emitter.onError(e -> sink.detach());
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

    /**
     * Reattaches a page to an answer that kept running after its stream was torn down.
     *
     * <p>Returns the cumulative text, the non-delta events after {@code since}, and whether the
     * worker is done. A run that is unknown, expired or belongs to another account is a plain
     * 404: distinguishing those cases would confirm that someone else's answer exists.
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> reattach(@PathVariable String id,
            @RequestParam(name = "since", defaultValue = "0") long since) {
        String username = AccessGuard.current().username();
        return runs.snapshot(id, username, since)
                .map(snapshot -> ResponseEntity.ok(snapshotBody(snapshot)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> snapshotBody(SupportRunStore.Snapshot snapshot) {
        List<Map<String, Object>> events = new ArrayList<>(snapshot.events().size());
        for (SupportRunStore.RunEvent event : snapshot.events()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("type", event.type());
            item.put("data", event.data());
            events.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", snapshot.id());
        body.put("done", snapshot.done());
        body.put("truncated", snapshot.truncated());
        body.put("text", snapshot.text());
        body.put("events", events);
        body.put("nextSeq", snapshot.nextSeq());
        return body;
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

    /**
     * Maps sink calls to SSE events; every send failure is swallowed after a client disconnect.
     *
     * <p>With a run store attached, each event is recorded before it is sent, so a page that
     * lost its stream can replay what it missed. Recording failures never break the live
     * stream — the buffer is a convenience, not the answer itself.
     */
    static final class SseSink implements SupportSink {
        private final SseEmitter emitter;
        private final SupportRunStore runs;
        private final String runId;
        private volatile boolean done;

        SseSink(SseEmitter emitter) {
            this(emitter, null, null);
        }

        SseSink(SseEmitter emitter, SupportRunStore runs, String runId) {
            this.emitter = emitter;
            this.runs = runs;
            this.runId = runId;
        }

        public void delta(String text) {
            send("delta", Map.of("text", text));
        }

        public void tool(String callId, String name, String labelKey, ToolState state) {
            send("tool", Map.of("id", callId, "name", name, "labelKey", labelKey,
                    "state", state.name()));
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
                // Mark the buffer first: a page that polls between these two lines must see
                // the run finished even if the final SSE frame never reaches a dead stream.
                if (runs != null) {
                    runs.complete(runId);
                }
                send("done", Map.of());
            }
        }

        void doneAndComplete() {
            done();
            detach();
        }

        /** Closes this page's stream only; the run and its single-flight lock keep going. */
        void detach() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed by a timeout or the client.
            }
        }

        private void send(String event, Object data) {
            Object payload = data;
            if (runs != null && data instanceof Map<?, ?> raw && !"done".equals(event)) {
                Map<String, Object> copy = new LinkedHashMap<>();
                raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
                long seq = runs.append(runId, event, copy);
                // The sequence is what a reattaching page resumes from; deltas are folded into
                // the cumulative text, so only the other events need to carry it.
                if (seq > 0 && !"delta".equals(event)) {
                    copy.put("seq", seq);
                    payload = copy;
                }
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException | RuntimeException e) {
                // The browser closed the panel or tab: nothing left to tell.
            }
        }
    }
}
