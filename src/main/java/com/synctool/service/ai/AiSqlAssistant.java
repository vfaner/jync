package com.synctool.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synctool.model.AiProvider;
import com.synctool.model.DatabaseType;

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
 * <p>The prompt therefore demands two things: the SQL, and a list of what the model could not
 * guarantee. The second is the more valuable output. A model that returns an empty uncertainty
 * list for a 200-line package body is telling you it did not look carefully, and the UI shows
 * the list next to the SQL so the reviewer knows where to concentrate.
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
     */
    private static final String SYSTEM_PROMPT = """
            You convert stored procedures, functions and views between SQL database products.

            Reply with a single JSON object and nothing else. No prose, no markdown fence:
            {"sql": "<the converted statement>", "uncertainties": ["<one concern per entry>"]}

            Rules:
            1. Output one complete, runnable CREATE statement for the target product. Do not
               include client-only directives such as DELIMITER, GO, or a trailing slash.
            2. Preserve the original logic. Do not add features, do not optimise, do not
               reformat beyond what the target's syntax requires.
            3. Never invent a table, column, parameter or function you were not shown. If the
               body references something whose definition you do not have, keep the reference
               exactly as written and add an entry to "uncertainties".
            4. "uncertainties" must list every behavioural difference you cannot rule out.
               Cover at least these when relevant: cursor and loop semantics, implicit
               transaction boundaries, NO_DATA_FOUND and other exception handling, NULL
               concatenation, integer versus decimal division, date arithmetic and format
               strings, row ordering where none is specified, and identifier case folding.
               An empty list means you are certain, so use it only when the body is trivial.
            5. If the conversion is not possible, set "sql" to an empty string and explain why
               in "uncertainties".
            """;

    private final AiProviderService providerService;
    private final AiChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

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
        AiProvider provider = providerService.activeProvider()
                .orElseThrow(() -> new IllegalStateException("error.ai.notAvailable"));

        if (sourceSql == null || sourceSql.isBlank()) {
            return Candidate.failure("error.ai.noSourceBody", "", 0);
        }

        String userPrompt = buildPrompt(objectType, name, sourceSql, mechanicalSql,
                sourceType, targetType);
        int budget = provider.getMaxTokens() == null || provider.getMaxTokens() <= 0
                ? 4096 : provider.getMaxTokens();

        AiChatClient.ChatResult result = client.complete(provider,
                providerService.decryptKey(provider), SYSTEM_PROMPT, userPrompt, budget);

        if (!result.isSuccess()) {
            return Candidate.failure(result.getMessage(), result.effectiveModel(),
                    result.getElapsedMs());
        }
        log.info("Drafted a {} candidate for '{}' ({} -> {}) using {}",
                objectType, name, sourceType, targetType, result.effectiveModel());
        return parse(result);
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

        if (mechanicalSql != null && !mechanicalSql.isBlank()
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
     * Reads the model's reply.
     *
     * <p>Models emit a markdown fence around JSON often enough that stripping one is not a
     * workaround but part of the contract. A reply that is not JSON at all is still used — the
     * SQL is usually fine and only the wrapper was ignored — but that fact becomes an
     * uncertainty of its own, because a model that ignored the output format may have ignored
     * the "do not invent" rule too.
     */
    private Candidate parse(AiChatClient.ChatResult result) {
        String text = result.getText() == null ? "" : result.getText().strip();
        if (text.isEmpty()) {
            return Candidate.failure("error.ai.emptyReply", result.effectiveModel(),
                    result.getElapsedMs());
        }

        String json = stripFence(text);
        try {
            JsonNode root = mapper.readTree(json);
            String sql = root.path("sql").asText("").strip();
            List<String> uncertainties = new ArrayList<>();
            for (JsonNode node : root.path("uncertainties")) {
                String entry = node.asText("").strip();
                if (!entry.isEmpty()) {
                    uncertainties.add(entry);
                }
            }
            if (sql.isEmpty()) {
                // Rule 5: the model declined. Its reasons are in the uncertainty list, so this
                // is a genuine answer rather than a transport failure -- report it as such.
                return Candidate.declined(uncertainties, result.effectiveModel(),
                        result.getElapsedMs());
            }
            return Candidate.success(sql, uncertainties, result.effectiveModel(),
                    result.getElapsedMs());

        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.debug("AI reply was not the requested JSON; treating it as bare SQL");
            List<String> uncertainties = new ArrayList<>();
            uncertainties.add("error.ai.unstructuredReply");
            return Candidate.success(stripFence(text), uncertainties, result.effectiveModel(),
                    result.getElapsedMs());
        }
    }

    /** Removes a surrounding ```json / ```sql fence if present, leaving the content alone. */
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
         * What the model could not guarantee. Entries beginning with {@code error.} are message
         * keys; the rest is free text from the model and is escaped, not interpreted, by the view.
         */
        private final List<String> uncertainties;
        /** Failure detail, or null on success. */
        private final String message;
        private final String model;
        private final long elapsedMs;

        private Candidate(boolean success, String sql, List<String> uncertainties, String message,
                          String model, long ms) {
            this.success = success;
            this.sql = sql;
            this.uncertainties = List.copyOf(uncertainties);
            this.message = message;
            this.model = model;
            this.elapsedMs = ms;
        }

        static Candidate success(String sql, List<String> uncertainties, String model, long ms) {
            return new Candidate(true, sql, uncertainties, null, model, ms);
        }

        /** The model answered but refused to convert; its reasons are the uncertainties. */
        static Candidate declined(List<String> uncertainties, String model, long ms) {
            return new Candidate(false, "", uncertainties, "error.ai.declined", model, ms);
        }

        static Candidate failure(String message, String model, long ms) {
            return new Candidate(false, "", List.of(), message, model, ms);
        }
    }
}
