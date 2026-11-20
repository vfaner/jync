package com.qqmu.jync.service.ai.support.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * Failed change-log rows in a bounded time window.
 *
 * <p>{@link AlertsTool} answers "what is broken right now" — a cleared condition is pruned from
 * the alert board as soon as its recovery is sent. An operator asking the next morning why the
 * night's numbers moved needs the opposite view: failures that already recovered. This tool
 * reads that history, capped at twenty rows and one week, and never the successful traffic.
 */
@Component
public class RecentFailuresTool implements SupportTool {

    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 168;
    private static final int MAX_SUMMARY = 200;

    private final ChangeLogRepository changes;
    private final ProjectRepository projects;

    public RecentFailuresTool(ChangeLogRepository changes, ProjectRepository projects) {
        this.changes = changes;
        this.projects = projects;
    }

    public String name() {
        return "recent_failures";
    }

    public String description() {
        return "List failed sync change-log rows from the last N hours (default 24, max 168), "
                + "newest first, capped at 20. Unlike alerts these include failures that have "
                + "already recovered. Pass projectId to narrow to one project. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "hours", Map.of("type", "integer"),
                        "projectId", Map.of("type", "integer")),
                "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("hours", "projectId");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        Long projectId = SupportArgs.optionalLong(args, "projectId");
        Long requested = SupportArgs.optionalLong(args, "hours");
        if (requested != null && (requested < 1 || requested > MAX_HOURS)) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        int hours = requested == null ? DEFAULT_HOURS : requested.intValue();
        Instant since = Instant.now().minus(Duration.ofHours(hours));

        List<ChangeLog> rows = projectId == null
                ? changes.findTop20BySuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(since)
                : changes.findTop20ByProjectIdAndSuccessFalseAndOccurredAtAfterOrderByOccurredAtDesc(
                        projectId, since);

        List<Long> ids = new ArrayList<>();
        for (ChangeLog row : rows) {
            if (row.getProjectId() != null && !ids.contains(row.getProjectId())) {
                ids.add(row.getProjectId());
            }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Project project : projects.findAllById(ids)) {
            names.put(project.getId(), project.getName());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeLog row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", row.getProjectId());
            item.put("project", names.get(row.getProjectId()));
            item.put("object", row.getObjectName());
            item.put("type", row.getChangeType() == null ? null : row.getChangeType().name());
            item.put("at", row.getOccurredAt() == null ? null : row.getOccurredAt().toString());
            String detail = row.getErrorDetail() != null
                    ? row.getErrorDetail() : row.getDetails();
            item.put("summary", clamp(detail));
            out.add(item);
        }
        return out;
    }

    private String clamp(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > MAX_SUMMARY ? detail.substring(0, MAX_SUMMARY) : detail;
    }
}
