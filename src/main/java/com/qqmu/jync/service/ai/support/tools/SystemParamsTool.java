package com.qqmu.jync.service.ai.support.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.settings.SystemTuningService;

import tools.jackson.databind.JsonNode;

/**
 * Read-only view of the administrator-editable system tuning whitelist. Values shown are the
 * effective ones (database override on top of application.yml) with the base value and whether
 * an override is active, so a recommendation can start from the real numbers.
 */
@Component
public class SystemParamsTool implements SupportTool {

    private final SystemTuningService tuning;

    public SystemParamsTool(SystemTuningService tuning) {
        this.tuning = tuning;
    }

    public String name() {
        return "system_params";
    }

    public String description() {
        return "Administrator-only read of the editable system performance parameters: "
                + "batch/fetch sizes, chunking, retry count, and the one-source-many-targets cap (sync.fanout.max-targets, 2-100). Returns each key, effective "
                + "value, application.yml base value, whether an override is active, and the "
                + "allowed range. Call it before proposing a change. No write. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of();
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().isAdmin()) {
            return Map.of("error", "error.accessDenied");
        }
        return Map.of("items", tuning.items());
    }
}
