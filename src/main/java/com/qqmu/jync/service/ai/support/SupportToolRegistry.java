package com.qqmu.jync.service.ai.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Whitelist of callable tools.
 *
 * <p>A name the model invents never reaches code: it becomes an error object the model can read
 * and explain. The same is true of malformed arguments and of a tool that throws — the SSE
 * stream must carry a sentence, not a stack trace.
 */
@Component
@Slf4j
public class SupportToolRegistry {

    /** One tool result may not flood the prompt; the tail is dropped and flagged. */
    static final int MAX_RESULT_CHARS = 8 * 1024;

    private final Map<String, SupportTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SupportToolRegistry(List<SupportTool> discovered) {
        for (SupportTool tool : discovered) {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate support tool: " + tool.name());
            }
        }
    }

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (SupportTool tool : tools.values()) {
            defs.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameters())));
        }
        return defs;
    }

    public String execute(String name, String argumentsJson, ToolContext ctx) {
        SupportTool tool = tools.get(name);
        if (tool == null) {
            return error("error.support.tool.unknown");
        }
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(argumentsJson);
        } catch (JacksonException e) {
            // A half-written JSON fragment is the model's mistake, not a server fault: say so
            // in the one vocabulary the model can act on.
            return error(SupportArgs.BAD_ARGS);
        }
        try {
            SupportArgs.onlyKeys(args, tool.allowedKeys());
            String json = mapper.writeValueAsString(tool.execute(args, ctx));
            if (json.length() > MAX_RESULT_CHARS) {
                ObjectNode truncated = mapper.createObjectNode();
                truncated.put("truncated", true);
                truncated.put("content", json.substring(0, MAX_RESULT_CHARS));
                return truncated.toString();
            }
            return json;
        } catch (IllegalArgumentException e) {
            String key = e.getMessage() != null && e.getMessage().startsWith("error.")
                    ? e.getMessage() : SupportArgs.BAD_ARGS;
            return error(key);
        } catch (RuntimeException e) {
            log.warn("Support tool '{}' failed: {}", name, e.toString());
            return error("error.support.tool.failed");
        }
    }

    private String error(String key) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", key);
        return node.toString();
    }
}
