package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import tools.jackson.databind.JsonNode;

@Component
public class ListConnectionsTool implements SupportTool {

    private static final int MAX_ROWS = 50;

    private final DatabaseConfigService configs;

    public ListConnectionsTool(DatabaseConfigService configs) {
        this.configs = configs;
    }

    public String name() {
        return "list_connections";
    }

    public String description() {
        return "List database connections by name; never returns host, user or URL.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "role", Map.of("type", "string", "enum", List.of("SOURCE", "TARGET")),
                        "query", Map.of("type", "string")),
                "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("role", "query");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String role = SupportArgs.optionalText(args, "role");
        String query = SupportArgs.optionalText(args, "query");
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DatabaseConfig config : configs.findAll()) {
            if (role != null && (config.getRole() == null
                    || !role.equalsIgnoreCase(config.getRole().name()))) {
                continue;
            }
            if (needle != null && (config.getName() == null
                    || !config.getName().toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            if (out.size() >= MAX_ROWS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", config.getId());
            row.put("name", config.getName());
            row.put("type", config.getType() == null ? null : config.getType().name());
            row.put("role", config.getRole() == null ? null : config.getRole().name());
            row.put("owner", config.getOwnerUsername());
            out.add(row);
        }
        return out;
    }
}
