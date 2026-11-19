package com.qqmu.jync.service.ai.support.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * What the alert evaluator currently considers broken: sync failures, stuck polling, parked
 * CDC, drift, unhealthy connections, slot backlog and skipped DDL, with the project each
 * belongs to and when it started.
 *
 * <p>Only active rows exist — a cleared condition is pruned when its recovery is sent — so
 * this is the live board, not a history. The change log (via project_status) is where a past
 * failure is read.
 */
@Component
public class AlertsTool implements SupportTool {

    private static final int MAX_ROWS = 20;

    private final AlertStateRepository states;

    public AlertsTool(AlertStateRepository states) {
        this.states = states;
    }

    public String name() {
        return "alerts";
    }

    public String description() {
        return "List the alert conditions active right now (sync failure, stuck polling, CDC "
                + "stopped, data drift, unhealthy connection, PG slot backlog, skipped DDL) "
                + "with the project name and when each started. No arguments. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of();
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AlertState state : states.findByActiveTrueOrderByFirstSeenAtDesc()) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", state.getCategory() == null
                    ? null : state.getCategory().name());
            row.put("projectId", state.getProjectId());
            row.put("subject", state.getSubject());
            row.put("object", state.getObjectKey());
            row.put("since", instant(state.getFirstSeenAt()));
            row.put("notifiedAt", instant(state.getLastNotifiedAt()));
            rows.add(row);
        }
        return rows;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
