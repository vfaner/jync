package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;
import com.qqmu.jync.service.metrics.ProjectMetricsView;

import tools.jackson.databind.JsonNode;

@Component
public class ProjectStatusTool implements SupportTool {

    private static final int MAX_PROGRESS_ROWS = 10;
    private static final int MAX_ERROR_CHARS = 200;

    private final ProjectService projects;
    private final SyncProgressRepository progressRows;
    private final ChangeLogRepository changes;
    private final MetricsQueryService metrics;

    public ProjectStatusTool(ProjectService projects, SyncProgressRepository progressRows,
                             ChangeLogRepository changes, MetricsQueryService metrics) {
        this.projects = projects;
        this.progressRows = progressRows;
        this.changes = changes;
        this.metrics = metrics;
    }

    public String name() {
        return "project_status";
    }

    public String description() {
        return "Read one project's running state, activity cycle, progress rows, last failure "
                + "and 1h throughput/lag. projectId comes from list_projects.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("projectId", Map.of("type", "integer")),
                "required", List.of("projectId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long projectId = SupportArgs.requireLong(args, "projectId");
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return Map.of("error", "error.project.not.found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", project.getName());
        out.put("running", projects.isRunning(project));

        ProjectService.CycleView cycle;
        try {
            cycle = projects.activeCycle(projectId);
        } catch (RuntimeException e) {
            // A status question must not fail because the lock table is momentarily unreadable.
            cycle = null;
        }
        if (cycle == null) {
            out.put("cycle", null);
        } else {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("startedAt", cycle.startedAt() == null ? null : cycle.startedAt().toString());
            c.put("minutesSinceProgress", cycle.minutesSinceProgress());
            c.put("stuck", cycle.stuck());
            out.put("cycle", c);
        }

        ChangeLog lastError = changes
                .findFirstByProjectIdAndSuccessFalseOrderByOccurredAtDesc(projectId)
                .orElse(null);
        if (lastError == null) {
            out.put("lastError", null);
        } else {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("occurredAt", lastError.getOccurredAt() == null
                    ? null : lastError.getOccurredAt().toString());
            String detail = lastError.getErrorDetail() != null
                    ? lastError.getErrorDetail() : lastError.getDetails();
            detail = detail == null ? "" : detail;
            e.put("summary", detail.length() > MAX_ERROR_CHARS
                    ? detail.substring(0, MAX_ERROR_CHARS) : detail);
            out.put("lastError", e);
        }

        List<Map<String, Object>> progress = new ArrayList<>();
        for (SyncProgress row : progressRows.findByProjectId(projectId)) {
            if (progress.size() >= MAX_PROGRESS_ROWS) {
                break;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("object", row.getObjectName());
            p.put("strategy", row.getCursorStrategy());
            p.put("lastSyncValue", row.getLastSyncValue());
            p.put("updatedAt", row.getLastSyncTime() == null
                    ? null : row.getLastSyncTime().toString());
            progress.add(p);
        }
        out.put("progress", progress);

        ProjectMetricsView view = metrics.project(projectId, MetricsRange.LAST_HOUR);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("rowsTotal", view.current().get("rowsTotal"));
        current.put("lag", view.current().get("lag"));
        current.put("failures", view.current().get("failures"));
        out.put("metrics", Map.of("window", "1h", "current", current));
        return out;
    }
}
