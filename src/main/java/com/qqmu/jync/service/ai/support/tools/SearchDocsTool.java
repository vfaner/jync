package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportKnowledgeBase;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import tools.jackson.databind.JsonNode;

@Component
public class SearchDocsTool implements SupportTool {

    private final SupportKnowledgeBase knowledge;

    public SearchDocsTool(SupportKnowledgeBase knowledge) {
        this.knowledge = knowledge;
    }

    public String name() {
        return "search_docs";
    }

    public String description() {
        return "Search the bundled Jync documentation for how-to, parameters, prerequisites and "
                + "limits. Answer only from the returned excerpts.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "topK", Map.of("type", "integer")),
                "required", List.of("query"));
    }

    public Set<String> allowedKeys() {
        return Set.of("query", "topK");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String query = SupportArgs.requireText(args, "query", 500);
        Long topK = SupportArgs.optionalLong(args, "topK");
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportKnowledgeBase.Section section : knowledge.search(ctx.language(), query,
                topK == null ? 3 : topK.intValue())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("section", section.title());
            row.put("excerpt", section.body());
            out.add(row);
        }
        return out;
    }
}
