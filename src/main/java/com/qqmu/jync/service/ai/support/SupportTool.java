package com.qqmu.jync.service.ai.support;

import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** One read-only capability the support assistant may invoke. */
public interface SupportTool {

    String name();

    /** English description shown to the model; not a user-facing string. */
    String description();

    /** JSON Schema object for the arguments. */
    Map<String, Object> parameters();

    /** Rejects unknown keys so a hallucinated field fails loudly. */
    Set<String> allowedKeys();

    /** Returns a JSON-serializable result; throwing is converted to an error object. */
    Object execute(JsonNode args, ToolContext ctx);
}
