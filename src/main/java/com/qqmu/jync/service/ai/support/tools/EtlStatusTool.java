package com.qqmu.jync.service.ai.support.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * ETL side of the same question: a task's schedule, when it last ran, whether it succeeded,
 * and the failure sentence the run page already shows.
 *
 * <p>One tool for both shapes — pass {@code taskId} for one task, {@code query} to match names,
 * or neither for the newest ten — because the model always starts from a name, and a separate
 * list tool would be one more id to resolve by hand.
 */
@Component
public class EtlStatusTool implements SupportTool {

    private static final int MAX_ROWS = 10;
    private static final int MAX_RESULT_DETAIL = 300;
    private static final int MAX_RESULT_LIST = 160;

    private final EtlTaskRepository tasks;

    public EtlStatusTool(EtlTaskRepository tasks) {
        this.tasks = tasks;
    }

    public String name() {
        return "etl_status";
    }

    public String description() {
        return "Read ETL task state: enabled, cron, write mode, last run time and success, and "
                + "the last run's result line. Pass taskId for one task, query to match task "
                + "names, or neither to list the newest 10. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "taskId", Map.of("type", "integer"),
                        "query", Map.of("type", "string")),
                "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("taskId", "query");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        Long taskId = SupportArgs.optionalLong(args, "taskId");
        String query = SupportArgs.optionalText(args, "query");
        if (taskId != null) {
            EtlTask task = tasks.findById(taskId).orElse(null);
            if (task == null) {
                return Map.of("error", "error.etl.not.found");
            }
            return detail(task);
        }
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EtlTask task : tasks.findAllByOrderByIdDesc()) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            String name = task.getName() == null ? "" : task.getName();
            if (needle != null && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("name", task.getName());
            row.put("enabled", task.getEnabled());
            row.put("lastRunTime", instant(task.getLastRunTime()));
            row.put("lastRunSuccess", task.getLastRunSuccess());
            row.put("lastRunResult", clamp(task.getLastRunResult(), MAX_RESULT_LIST));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> detail(EtlTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("name", task.getName());
        out.put("enabled", task.getEnabled());
        out.put("cron", task.getCronExpression());
        out.put("writeMode", task.getWriteMode() == null ? null : task.getWriteMode().name());
        out.put("targetTable", task.getTargetTable());
        out.put("lastRunTime", instant(task.getLastRunTime()));
        out.put("lastRunSuccess", task.getLastRunSuccess());
        out.put("lastRunResult", clamp(task.getLastRunResult(), MAX_RESULT_DETAIL));
        out.put("runIndex", task.getRunIndex());
        return out;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String clamp(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
