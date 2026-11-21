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

import tools.jackson.databind.JsonNode;

@Component
public class ProjectStatusTool implements SupportTool {

    private static final int MAX_PROGRESS_ROWS = 10;

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
            e.put("summary", ToolText.safe(detail, ToolText.MAX_SUMMARY));
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
            // 断点水位值是业务值（账号、时间戳等），数据边界文档承诺只发“是否存在”。
            p.put("hasSyncValue", row.getLastSyncValue() != null);
            p.put("updatedAt", row.getLastSyncTime() == null
                    ? null : row.getLastSyncTime().toString());
            progress.add(p);
        }
        out.put("progress", progress);

        // 只取最新三个数字，不构建曲线/变更聚合（诊断高频调用，省两条 change_log 全扫描）。
        Map<String, Double> current = metrics.projectCurrent(projectId);
        out.put("metrics", Map.of("window", "1h", "current", current));
        return out;
    }
}
