package com.qqmu.jync.service.ai.support.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.JsonNode;

/**
 * Turns "the Elasticsearch migration stopped" into the numeric id every other migration tool
 * takes. Dedicated-migration tasks live outside the project vocabulary, so list_projects can
 * never answer this.
 */
@Component
public class ListMigrationsTool implements SupportTool {

    private static final int MAX_ROWS = 10;
    private static final int MAX_RESULT_LIST = 160;

    private final MigrationTaskRepository tasks;
    private final MigrationTaskService migrationTasks;

    public ListMigrationsTool(MigrationTaskRepository tasks, MigrationTaskService migrationTasks) {
        this.tasks = tasks;
        this.migrationTasks = migrationTasks;
    }

    public String name() {
        return "list_migrations";
    }

    public String description() {
        return "List dedicated-migration tasks (Elasticsearch/OpenSearch, MongoDB, KingbaseES) "
                + "with id, type, enabled, last run time and success. Pass query to match task "
                + "names, type to match one migration type, or neither to list the newest 10. "
                + "Call it first to resolve the taskId used by diagnose_migration and "
                + "control_migration.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "type", Map.of("type", "string",
                                "enum", List.of("ES_TO_EASYSEARCH", "MONGODB_TO_MONGODB",
                                        "MONGODB_TO_KINGBASE"))),
                "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("query", "type");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String query = SupportArgs.optionalText(args, "query");
        String type = SupportArgs.optionalText(args, "type");
        MigrationType wanted = type == null ? null : parseType(type);
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);

        List<Map<String, Object>> out = new ArrayList<>();
        for (MigrationTask task : tasks.findAllByOrderByIdDesc()) {
            if (out.size() >= MAX_ROWS) {
                break;
            }
            if (wanted != null && task.getType() != wanted) {
                continue;
            }
            String name = task.getName() == null ? "" : task.getName();
            if (needle != null && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("name", task.getName());
            row.put("type", task.getType() == null ? null : task.getType().name());
            row.put("enabled", task.getEnabled());
            row.put("cron", task.getCronExpression());
            row.put("running", isRunning(task.getId()));
            row.put("lastRunTime", instant(task.getLastRunTime()));
            row.put("lastRunSuccess", task.getLastRunSuccess());
            row.put("lastRunResult", clamp(task.getLastRunResult(), MAX_RESULT_LIST));
            out.add(row);
        }
        return out;
    }

    private MigrationType parseType(String raw) {
        try {
            return MigrationType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
    }

    private boolean isRunning(Long id) {
        try {
            return id != null && migrationTasks.isRunning(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String clamp(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
