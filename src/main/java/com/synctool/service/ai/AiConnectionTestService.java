package com.synctool.service.ai;

import org.springframework.stereotype.Service;

import com.synctool.model.AiProvider;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Probes an AI endpoint by asking it to complete one token.
 *
 * <p>A TCP or {@code HEAD} probe would report success for a reachable host with a wrong key,
 * a misspelled model or a base URL that is one path segment off. Those are the three things
 * that actually go wrong, and only a real completion request surfaces them, so the probe sends
 * one with {@code max_tokens: 1} through the same {@link AiChatClient} the assistant uses —
 * a probe that took a different code path could pass while real requests failed.
 *
 * <p>The API key is never logged and never placed in a result message. Only the resolved URL
 * and the model name are reported back, which is what the user needs to fix a misconfiguration.
 */
@Service
@Slf4j
public class AiConnectionTestService {

    private final AiChatClient client;

    public AiConnectionTestService(AiChatClient client) {
        this.client = client;
    }

    /**
     * Sends a minimal completion request.
     *
     * @param plainApiKey the decrypted key; callers must not pass the stored ciphertext
     * @return the outcome, never null; failures are reported rather than thrown so the UI can
     *         show the endpoint's own error body, which usually names the problem exactly
     */
    public TestResult test(AiProvider provider, String plainApiKey) {
        AiChatClient.ChatResult result = client.complete(provider, plainApiKey, null, "ping", 1);

        if (!result.isSuccess()) {
            return TestResult.failure(result.getMessage(), result.getEndpoint(),
                    result.getRequestedModel(), result.getElapsedMs());
        }
        log.info("AI endpoint test OK for '{}' ({} at {})",
                provider.getName(), result.effectiveModel(), result.getEndpoint());
        return TestResult.success(describeSuccess(result), result.getEndpoint(),
                result.getRequestedModel(), result.getElapsedMs());
    }

    /** Names the model the endpoint says it used, which can differ from the one requested. */
    private String describeSuccess(AiChatClient.ChatResult result) {
        String served = result.getServedModel();
        if (served != null && !served.isBlank() && !served.equals(result.getRequestedModel())) {
            // Gateways silently remap aliases; knowing which model answered matters when the
            // conversion quality is later judged.
            return "OK — served by " + served;
        }
        return "OK";
    }

    /** Outcome of an AI endpoint test. Mirrors {@code ConnectionTestService.TestResult}. */
    @Getter
    public static class TestResult {
        private final boolean success;
        private final String message;
        private final String endpoint;
        private final String model;
        private final long elapsedMs;

        private TestResult(boolean success, String message, String endpoint, String model, long ms) {
            this.success = success;
            this.message = message;
            this.endpoint = endpoint;
            this.model = model;
            this.elapsedMs = ms;
        }

        static TestResult success(String message, String endpoint, String model, long ms) {
            return new TestResult(true, message, endpoint, model, ms);
        }

        static TestResult failure(String message, String endpoint, String model, long ms) {
            return new TestResult(false, message, endpoint, model, ms);
        }
    }
}
