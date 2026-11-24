package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ProjectTuning;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.metrics.MetricsQueryService;

import tools.jackson.databind.JsonNode;

@Component
public class ProjectStatusTool implements SupportTool {

    private static final int MAX_PROGRESS_ROWS = 10;
    private static final int MAX_CURSOR_COLUMNS = 50;

    private final ProjectService projects;
    private final SyncProgressRepository progressRows;
    private final ChangeLogRepository changes;
    private final MetricsQueryService metrics;
    private final DatabaseConfigService databases;
    private final SyncProperties properties;

    public ProjectStatusTool(ProjectService projects, SyncProgressRepository progressRows,
                             ChangeLogRepository changes, MetricsQueryService metrics,
                             DatabaseConfigService databases, SyncProperties properties) {
        this.projects = projects;
        this.progressRows = progressRows;
        this.changes = changes;
        this.metrics = metrics;
        this.databases = databases;
        this.properties = properties;
    }

    public String name() {
        return "project_status";
    }

    public String description() {
        return "Read one project's details and running state: source/target connection "
                + "names and types, capture strategy, selected-table count, sync switches, "
                + "next scheduled run, activity cycle, progress rows, last failure, 1h "
                + "throughput/lag, and the current tuning values with their defaults. For "
                + "one table's state use project_table_status. projectId comes from "
                + "list_projects.";
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
        out.put("enabled", Boolean.TRUE.equals(project.getEnabled()));
        out.put("owner", project.getOwnerUsername());
        out.put("targetKind", project.getTargetKindOrDefault().name());
        out.put("source", connection(project.getSourceDbId()));
        out.put("target", connection(project.getTargetDbId()));

        // The task's "detail" is partly configuration, which lives in a JSON column: a corrupt
        // or mid-migration row must not turn a status question into a 500. The state, cycle
        // and failures above are still valid evidence, so report the gap and keep going.
        try {
            SyncConfig config = projects.loadConfig(project);
            out.put("captureStrategy", config.getCaptureStrategyOrDefault().name());
            out.put("selectedTables",
                    config.getTables() == null ? 0 : config.getTables().size());
            out.put("syncData", config.isSyncData());
            out.put("syncStructure", config.isSyncStructure());
            out.put("syncDeletes", config.isSyncDeletes());
            // A 700-table project can carry 700 overrides; the registry would cut the whole
            // JSON at 8 KB and lose the rest of the answer. Cap the sample and say so.
            Map<String, String> columns = config.getCursorColumns();
            if (columns != null && columns.size() > MAX_CURSOR_COLUMNS) {
                Map<String, String> sample = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : columns.entrySet()) {
                    if (sample.size() >= MAX_CURSOR_COLUMNS) {
                        break;
                    }
                    sample.put(entry.getKey(), entry.getValue());
                }
                out.put("cursorColumns", sample);
                out.put("cursorColumnsTruncated", true);
            } else {
                out.put("cursorColumns", columns);
            }
            out.put("cursorColumnPriority", config.getCursorColumnPriority());
            out.put("cronExpression", config.getCronExpression());
            // Current tuning plus the global defaults the engine falls back to: the model
            // needs both before recommending a change, and null configured values mean
            // "default", not "unset and broken".
            Map<String, Object> tuning = ProjectTuning.view(config);
            tuning.put("batchSizeDefault", properties.getBatchSize());
            tuning.put("fullLoadParallelismDefault", properties.getFullLoadParallelism());
            tuning.put("pollIntervalDefaultMs", properties.getPollInterval());
            out.put("tuning", tuning);
        } catch (RuntimeException e) {
            out.put("configUnavailable", true);
        }
        try {
            java.time.LocalDateTime next = projects.nextFireTime(projectId);
            out.put("nextFireAt", next == null ? null : next.toString());
        } catch (RuntimeException e) {
            out.put("nextFireAt", null);
        }

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

    /** Connection name and product only — never host, URL, user or password. */
    private Map<String, Object> connection(Long id) {
        if (id == null) {
            return null;
        }
        DatabaseConfig config = databases.findById(id).orElse(null);
        if (config == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", config.getName());
        out.put("type", config.effectiveType().name());
        return out;
    }
}
