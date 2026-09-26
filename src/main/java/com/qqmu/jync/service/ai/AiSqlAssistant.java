package com.qqmu.jync.service.ai;

import java.util.List;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AiProvider;
import com.qqmu.jync.model.DatabaseType;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the configured model to draft a routine body for the target product.
 *
 * <p>What comes back is a <em>candidate</em>, never an applied change. Nothing in this class
 * writes to a database, and the sync path does not call it: a candidate reaches the target only
 * after a human reads it and saves it as a DDL override. That boundary is the whole design. A
 * model asked to translate PL/SQL into MySQL will always produce something that looks right,
 * and the failure mode of accepting that silently is far worse than the mechanical converter's
 * habit of refusing outright — a refusal is visible, a plausible wrong cursor is not.
 *
 * <p>The prompt therefore demands one thing — the converted statement — and the page demands
 * the other half of the safety case: an AI draft is a candidate, and the reviewer is told in
 * plain words to test it against the target before saving it as an override.
 */
@Service
@Slf4j
public class AiSqlAssistant {

    /**
     * Instructions the model gets on every request.
     *
     * <p>Written as constraints rather than encouragement. "Do not invent" is load-bearing:
     * asked to convert a body referencing a table it cannot see, a model will otherwise supply a
     * plausible column list, and a reviewer skimming the diff will not catch it.
     *
     * <p>The reply is the statement alone — no JSON wrapper, no self-reported caveat list. The
     * wrapper cost output tokens on every draft for a list the reviewer read as noise, and the
     * honest safeguard is the one the page states instead: an AI draft is a candidate a human
     * must test against the target before it is saved.
     */
    private static final String SYSTEM_PROMPT = """
            You convert stored procedures, functions and views between SQL database products.

            Reply with the converted CREATE statement and nothing else. No prose, no
            markdown fence, no JSON wrapper.

            Rules:
            1. Output one complete, runnable CREATE statement for the target product. Do not
               include client-only directives such as DELIMITER, GO, or a trailing slash.
            2. Preserve the original logic. Do not add features, do not optimise, do not
               reformat beyond what the target's syntax requires.
            3. Never invent a table, column, parameter or function you were not shown. If the
               body references something whose definition you do not have, keep the reference
               exactly as written.
            4. If the conversion is not possible, reply with nothing at all.
            """;

    private final AiProviderService providerService;
    private final AiChatClient client;

    public AiSqlAssistant(AiProviderService providerService, AiChatClient client) {
        this.providerService = providerService;
        this.client = client;
    }

    /**
     * Drafts a target-dialect version of one routine or view body.
     *
     * @param objectType      {@code PROCEDURE}, {@code FUNCTION} or {@code VIEW}, for the prompt
     * @param name            object name, so the model keeps the header consistent
     * @param sourceSql       the original body as the source product stores it
     * @param mechanicalSql   what {@code SqlBodyConverter} produced, or null if it produced
     *                        nothing useful; given to the model as a starting point because the
     *                        mechanical function-name mapping is already known-correct
     * @param sourceType      source product
     * @param targetType      target product
     * @return the candidate, never null; check {@link Candidate#isSuccess()}
     * @throws IllegalStateException when no provider is enabled, so callers cannot accidentally
     *                               treat an unconfigured install as a conversion failure
     */
    public Candidate draft(String objectType, String name, String sourceSql, String mechanicalSql,
                           DatabaseType sourceType, DatabaseType targetType) {
        return run(objectType, name, sourceSql, mechanicalSql, sourceType, targetType,
                (provider, key, prompt, budget, sink) -> client.complete(provider, key,
                        SYSTEM_PROMPT, prompt, budget));
    }

    /**
     * As {@link #draft}, but each text delta reaches {@code sink} as the model emits it, so the
     * reviewer watches the candidate being written instead of watching a spinner.
     */
    public Candidate draftStream(String objectType, String name, String sourceSql,
                                 String mechanicalSql, DatabaseType sourceType,
                                 DatabaseType targetType,
                                 java.util.function.Consumer<String> sink) {
        return run(objectType, name, sourceSql, mechanicalSql, sourceType, targetType,
                (provider, key, prompt, budget, ignored) -> client.stream(provider, key,
                        SYSTEM_PROMPT, prompt, budget, sink));
    }

    private Candidate run(String objectType, String name, String sourceSql, String mechanicalSql,
                          DatabaseType sourceType, DatabaseType targetType, Call call) {
        AiProvider provider = providerService.activeProvider()
                .orElseThrow(() -> new IllegalStateException("error.ai.notAvailable"));

        if (sourceSql == null || sourceSql.isBlank()) {
            return Candidate.failure("error.ai.noSourceBody", "", 0);
        }

        String userPrompt = buildPrompt(objectType, name, sourceSql, mechanicalSql,
                sourceType, targetType);
        int budget = provider.getMaxTokens() == null || provider.getMaxTokens() <= 0
                ? 4096 : provider.getMaxTokens();

        AiChatClient.ChatResult result = call.invoke(provider,
                providerService.decryptKey(provider), userPrompt, budget, text -> { });

        if (!result.isSuccess()) {
            return Candidate.failure(result.getMessage(), result.effectiveModel(),
                    result.getElapsedMs());
        }
        log.info("Drafted a {} candidate for '{}' ({} -> {}) using {}",
                objectType, name, sourceType, targetType, result.effectiveModel());
        return parse(result);
    }

    /** The one difference between the buffered and the streaming call. */
    private interface Call {
        AiChatClient.ChatResult invoke(AiProvider provider, String apiKey, String userPrompt,
                                       int budget, java.util.function.Consumer<String> sink);
    }

    private String buildPrompt(String objectType, String name, String sourceSql,
                               String mechanicalSql, DatabaseType sourceType,
                               DatabaseType targetType) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source product: ").append(describe(sourceType)).append('\n');
        sb.append("Target product: ").append(describe(targetType)).append('\n');
        sb.append("Object type: ").append(objectType).append('\n');
        sb.append("Object name: ").append(name).append("\n\n");
        sb.append("Original definition as stored by the source:\n");
        sb.append("```sql\n").append(sourceSql.strip()).append("\n```\n");

        // Within one dialect family the mechanical pass is nearly a no-op and the page says so;
        // sending a second copy of the same body would double the input tokens for nothing.
        boolean sameFamily = sourceType != null && targetType != null
                && sourceType.getFamily() == targetType.getFamily();
        if (!sameFamily
                && mechanicalSql != null && !mechanicalSql.isBlank()
                && !mechanicalSql.strip().equals(sourceSql.strip())) {
            // The mechanical pass already mapped function names and syntax markers correctly.
            // Showing it saves the model that work and, more usefully, anchors it to the
            // project's own conventions instead of whatever it would pick.
            sb.append("\nA mechanical converter produced this, which handles function names and\n")
                    .append("syntax markers but not procedural control flow. Treat it as a\n")
                    .append("starting point and correct it where it is wrong:\n");
            sb.append("```sql\n").append(mechanicalSql.strip()).append("\n```\n");
        }
        return sb.toString();
    }

    /** Product name plus dialect family, since family is what actually drives the syntax. */
    private String describe(DatabaseType type) {
        if (type == null) {
            return "unknown";
        }
        return type.getDisplayName() + " (" + type.getFamily() + "-compatible dialect)";
    }

    /**
     * Reads the model's reply, which is the statement itself.
     *
     * <p>Models still wrap the statement in a markdown fence often enough that stripping one is
     * not a workaround but part of the contract. What is left after stripping is used verbatim;
     * nothing is parsed, because there is no wrapper to parse.
     */
    private Candidate parse(AiChatClient.ChatResult result) {
        String text = result.getText() == null ? "" : result.getText().strip();
        if (text.isEmpty()) {
            return Candidate.failure("error.ai.emptyReply", result.effectiveModel(),
                    result.getElapsedMs());
        }

        String sql = stripFence(text);
        if (sql.isEmpty()) {
            // Rule 4: the model answered nothing usable, which is its way of declining.
            return Candidate.declined(List.of(), result.effectiveModel(), result.getElapsedMs());
        }
        return Candidate.success(sql, List.of(), result.effectiveModel(), result.getElapsedMs());
    }

    /** Removes a surrounding markdown fence if present, leaving the content alone. */
    private String stripFence(String text) {
        String t = text.strip();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        if (firstNewline < 0) {
            return t;
        }
        int closing = t.lastIndexOf("```");
        if (closing <= firstNewline) {
            // An opening fence with no close: the reply was truncated by the token budget.
            return t.substring(firstNewline + 1).strip();
        }
        return t.substring(firstNewline + 1, closing).strip();
    }

    /** A drafted conversion awaiting human review. Immutable; carries no credentials. */
    @Getter
    public static class Candidate {
        private final boolean success;
        /** The drafted statement, empty when the model declined or the call failed. */
        private final String sql;
        /**
         * Per-draft caveats. The prompt no longer asks the model for any — the page states the
         * one that matters, "test this yourself" — so this stays empty and reserved.
         */
        private final List<String> uncertainties;
        /** Failure detail, or null on success. */
        private final String message;
        private final String model;
        private final long elapsedMs;
        /** True when served from the short-lived draft cache rather than a fresh model call. */
        private final boolean cached;

        private Candidate(boolean success, String sql, List<String> uncertainties, String message,
                          String model, long ms, boolean cached) {
            this.success = success;
            this.sql = sql;
            this.uncertainties = List.copyOf(uncertainties);
            this.message = message;
            this.model = model;
            this.elapsedMs = ms;
            this.cached = cached;
        }

        static Candidate success(String sql, List<String> uncertainties, String model, long ms) {
            return new Candidate(true, sql, uncertainties, null, model, ms, false);
        }

        /** Re-serves an earlier candidate; the elapsed time of the original call no longer applies. */
        static Candidate cached(Candidate original) {
            return new Candidate(original.success, original.sql, original.uncertainties,
                    original.message, original.model, 0, true);
        }

        /** The model answered but refused to convert; its reasons are the uncertainties. */
        static Candidate declined(List<String> uncertainties, String model, long ms) {
            return new Candidate(false, "", uncertainties, "error.ai.declined", model, ms, false);
        }

        static Candidate failure(String message, String model, long ms) {
            return new Candidate(false, "", List.of(), message, model, ms, false);
        }
    }
}
