package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ProjectTuning;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Batch cursor-column overrides and the project-level priority list, on one card.
 *
 * <p>Cursor columns only exist on the polling path: log CDC replays the source's changes, a
 * full compare reads both sides, and a Redis target has no cursor. The tool mirrors the page
 * rule and refuses those projects instead of storing a setting that can never take effect.
 * The click re-reads the config and calls {@link ProjectService#saveCursorSettings}, which
 * clears the per-table cursor for every table whose effective column changed, so the table is
 * re-loaded rather than continuing from a watermark taken with a different column.
 */
@Component
public class UpdateProjectCursorTool implements SupportTool {

    private static final String NOTE_KEY = "support.action.cursor.note";
    /** One card must stay readable and under the tool-result cap; ask for a second card. */
    private static final int MAX_CHANGES = 20;
    /** The registry cuts tool results at 8 KB; leave room for the envelope. */
    private static final int MAX_CARD_CHARS = 6000;

    private final ProjectService projects;
    private final SupportActionStore actions;
    private final ObjectMapper mapper = new ObjectMapper();

    public UpdateProjectCursorTool(ProjectService projects, SupportActionStore actions) {
        this.projects = projects;
        this.actions = actions;
    }

    public String name() {
        return "update_project_cursor";
    }

    public String description() {
        return "Prepare a batch change to one project's cursor columns and/or its ordered "
                + "cursor-column priority list, shown as a confirmation card. columns maps "
                + "table name to cursor column (blank removes the override and returns to "
                + "auto-detection); priority is an ordered list (max 20); at most 20 changes "
                + "per card — split a larger batch into several cards. Only polling "
                + "projects are accepted — log CDC, full compare and Redis targets do not use "
                + "cursor columns. The project must be stopped, and only projects the caller "
                + "may edit are accepted. Changing a table's effective cursor re-loads that "
                + "table on the next cycle. projectId comes from list_projects.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("type", "object");
        columns.put("description", "Table name to cursor column; a blank value removes it.");
        columns.put("additionalProperties", Map.of("type", "string"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of("type", "integer"));
        properties.put("columns", columns);
        properties.put("priority", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "Ordered preference, left to right; empty clears it."));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("projectId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "columns", "priority");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long projectId = SupportArgs.requireLong(args, "projectId");
        Map<String, String> requested = columns(args.path("columns"));
        boolean priorityProvided = args.has("priority") && !args.path("priority").isNull();
        List<String> priority = priorityProvided ? priority(args.path("priority")) : null;
        if (requested.isEmpty() && priority == null) {
            return error("error.support.param.empty");
        }

        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return error("error.project.not.found");
        }
        if (!ctx.actor().canEdit(project.getOwnerUsername())) {
            return error("error.accessDenied");
        }
        if (projects.isRunning(project)) {
            return error(Boolean.TRUE.equals(project.getEnabled())
                    ? "error.project.config.running" : "error.project.stillStopping");
        }

        SyncConfig config = projects.loadConfig(project);
        ProjectTuning.requireCursorApplicable(config, project.getTargetKindOrDefault());
        Map<String, String> normalized = ProjectTuning.normalizeColumns(config, requested);
        List<String> normalizedPriority = priority == null
                ? null : ProjectTuning.normalizePriority(priority);

        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        int changes = 0;
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            String table = entry.getKey();
            String next = entry.getValue().isBlank() ? null : entry.getValue();
            String old = config.cursorColumnFor(table);
            String previous = old == null || old.isBlank() ? null : old.trim();
            if (java.util.Objects.equals(previous, next)) {
                continue;
            }
            changes++;
            fields.add(field("cursor:" + table, table, previous, next));
            payload.computeIfAbsent("columns", key -> new LinkedHashMap<String, String>());
            @SuppressWarnings("unchecked")
            Map<String, String> kept = (Map<String, String>) payload.get("columns");
            kept.put(table, next == null ? "" : next);
        }
        if (normalizedPriority != null) {
            List<String> previous = config.getCursorColumnPriority() == null
                    ? List.of() : config.getCursorColumnPriority();
            if (!ProjectTuning.samePriority(previous, normalizedPriority)) {
                changes++;
                fields.add(field("cursorPriority", "cursor.priority.title",
                        join(previous), join(normalizedPriority)));
                payload.put("priority", normalizedPriority);
            }
        }
        if (changes == 0) {
            return error("error.support.param.unchanged");
        }
        if (changes > MAX_CHANGES || tooLarge(fields)) {
            return error("error.support.param.tooMany");
        }

        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.PROJECT,
                project.getId(), SupportActionStore.Action.UPDATE_CURSOR, project.getName(),
                payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(
                SupportActionStore.Kind.PROJECT, SupportActionStore.Action.UPDATE_CURSOR));
        out.put("resourceKindKey", "support.action.resource.project");
        out.put("resourceName", project.getName());
        out.put("needsConfirmation", true);
        out.put("fields", fields);
        out.put("noteKey", NOTE_KEY);
        return out;
    }

    private Map<String, Object> field(String field, String labelKey, String before, String after) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("field", field);
        row.put("labelKey", labelKey);
        row.put("before", before == null ? "" : before);
        row.put("beforeIsDefault", before == null || before.isEmpty());
        row.put("after", after == null ? "" : after);
        row.put("afterIsDefault", after == null || after.isEmpty());
        return row;
    }

    private boolean tooLarge(Object value) {
        try {
            return mapper.writeValueAsString(value).length() > MAX_CARD_CHARS;
        } catch (Exception e) {
            return true;
        }
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(", ", values);
    }

    private Map<String, String> columns(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            if (out.size() >= 200) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            out.put(entry.getKey(), entry.getValue().asString(""));
        }
        return out;
    }

    private List<String> priority(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            out.add(item.asString(""));
        }
        return out;
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
