package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * Per-table sync state inside one project: completed, still loading, interrupted, or never
 * started.
 *
 * <p>This is the question {@code project_status} cannot answer: its progress rows only exist for
 * tables that already ran, so "has T_ORDER finished?" would otherwise be answered from a task
 * level flag that says nothing about that table. {@link ProjectService#findTableSyncStatuses}
 * derives the state from the selected-table list plus progress and checkpoint rows — a cheap
 * metadata read, no source or target connection is opened.
 *
 * <p>A large project can select hundreds of tables, so the result carries counts for every state
 * and returns at most {@code limit} rows; the model is told to report the total and call again
 * with a {@code state} or {@code table} filter instead of pretending the sample is complete.
 */
@Component
public class ProjectTableStatusTool implements SupportTool {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final int SAMPLE_NAMES = 10;

    private final ProjectService projects;

    public ProjectTableStatusTool(ProjectService projects) {
        this.projects = projects;
    }

    public String name() {
        return "project_table_status";
    }

    public String description() {
        return "Per-table sync state of one project: COMPLETED (initial full load done; "
                + "fullLoadPercent is 100 unless externalBaseline), IN_PROGRESS (full load "
                + "running/resumable, or a cleared cursor is waiting for its full reload), "
                + "INCOMPLETE (the initial load never finished, or a cleared cursor is awaiting "
                + "the next reload; a polling task may show this between cycles), NOT_STARTED "
                + "(selected but no progress yet), incrementalState (RUNNING/PAUSED/ERROR/"
                + "LIMITED/WAITING), plus rowsSyncedTotal (full load counts rows copied; "
                + "incremental cycles count actual changes), lastSyncTime (for an active full "
                + "load this is the newest committed checkpoint), the current full-load rows "
                + "copied and percentage, and externalBaseline (the table was seeded by an "
                + "adopted external dump, not a Jync full load). Optional table "
                + "(case-insensitive substring) and state filters. Cheap metadata read. "
                + "projectId comes from list_projects.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of("type", "integer"));
        properties.put("table", Map.of("type", "string",
                "description", "Case-insensitive table-name substring; omit for all tables."));
        properties.put("state", Map.of("type", "string",
                "enum", List.of("COMPLETED", "IN_PROGRESS", "INCOMPLETE", "NOT_STARTED")));
        properties.put("limit", Map.of("type", "integer",
                "description", "Rows to return, default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + "."));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("projectId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "table", "state", "limit");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long projectId = SupportArgs.requireLong(args, "projectId");
        String table = SupportArgs.optionalText(args, "table");
        if (table != null && table.length() > 128) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        String requestedState = SupportArgs.optionalText(args, "state");
        ProjectService.SyncState state = parseState(requestedState);
        Long requestedLimit = SupportArgs.optionalLong(args, "limit");
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit.intValue();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }

        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return Map.of("error", "error.project.not.found");
        }
        SyncConfig config;
        try {
            config = projects.loadConfig(project);
        } catch (RuntimeException e) {
            // Without the selected-table list "never started" cannot be distinguished from
            // "not selected"; failing beats inventing a state for every table.
            return Map.of("error", "error.project.config");
        }
        List<ProjectService.TableSyncStatus> all =
                projects.findTableSyncStatuses(projectId, config);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.getName());
        out.put("running", projects.isRunning(project));
        // A legacy config selected "everything" and has no local table list; the status list
        // then contains only tables that already have progress, so NOT_STARTED cannot be seen.
        out.put("selectionKnown", config.getTables() != null && !config.getTables().isEmpty());
        out.put("summary", summary(all));

        String needle = table == null ? null : table.toUpperCase(Locale.ROOT);
        List<ProjectService.TableSyncStatus> matched = new ArrayList<>();
        for (ProjectService.TableSyncStatus row : all) {
            if (needle != null && (row.name() == null
                    || !row.name().toUpperCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            if (state != null && row.state() != state) {
                continue;
            }
            matched.add(row);
        }
        out.put("matched", matched.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectService.TableSyncStatus row : matched) {
            if (rows.size() >= limit) {
                break;
            }
            rows.add(table(row));
        }
        out.put("tables", rows);
        out.put("truncated", matched.size() > rows.size());
        if (matched.isEmpty() && table != null) {
            // "There is no such table" and "it is spelled differently" look identical to the
            // model; shipping a few real names lets it ask a precise follow-up instead of
            // guessing. Names are metadata, never row data.
            List<String> names = new ArrayList<>();
            for (ProjectService.TableSyncStatus row : all) {
                if (names.size() >= SAMPLE_NAMES) {
                    break;
                }
                names.add(row.name());
            }
            out.put("knownTables", names);
        }
        return out;
    }

    private Map<String, Object> summary(List<ProjectService.TableSyncStatus> rows) {
        int completed = 0;
        int inProgress = 0;
        int incomplete = 0;
        int notStarted = 0;
        for (ProjectService.TableSyncStatus row : rows) {
            switch (row.state()) {
                case COMPLETED -> completed++;
                case IN_PROGRESS -> inProgress++;
                case INCOMPLETE -> incomplete++;
                case NOT_STARTED -> notStarted++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("selected", rows.size());
        summary.put("completed", completed);
        summary.put("inProgress", inProgress);
        summary.put("incomplete", incomplete);
        summary.put("notStarted", notStarted);
        return summary;
    }

    private Map<String, Object> table(ProjectService.TableSyncStatus row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", row.name());
        out.put("state", row.state().name());
        out.put("incrementalState", row.incremental().name());
        out.put("cursorStrategy", row.cursorStrategy());
        out.put("cursorColumn", row.cursorColumn());
        out.put("resumableLoad", row.resumableLoad());
        out.put("rowsSyncedTotal", row.rowsSyncedTotal());
        out.put("lastSyncTime", row.lastSyncTime() == null
                ? null : row.lastSyncTime().toString());
        out.put("fullLoadRowsCopied", row.rowsCopied());
        out.put("fullLoadPercent", row.fullLoadPercent());
        out.put("fullLoadCompletedAt", row.fullLoadCompletedAt() == null
                ? null : row.fullLoadCompletedAt().toString());
        out.put("externalBaseline", row.externalBaseline());
        out.put("fullLoadUpdatedAt", row.lastUpdatedAt() == null
                ? null : row.lastUpdatedAt().toString());
        if (row.targetsTotal() != null) {
            out.put("targetsDone", row.targetsDone());
            out.put("targetsTotal", row.targetsTotal());
        }
        return out;
    }

    private ProjectService.SyncState parseState(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ProjectService.SyncState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
    }
}
