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

/**
 * Prepares a tuning change for one project. Nothing is written: the tool validates the
 * whitelist, ranges, strategy applicability, ownership and the stopped state, then stores the
 * change behind a single-use token. {@code SupportActionService} applies it only after the user
 * clicks, re-checking every guard against the fresh row.
 *
 * <p>Refusing at preparation time matters for the conversation: a card that is guaranteed to
 * fail on click teaches the operator the wrong thing about permissions or the running rule.
 * A running project gets an error naming the reason instead, so the assistant can offer a Stop
 * card first (the two actions are deliberately never combined — one click must not both stop
 * production traffic and change its parameters).
 */
@Component
public class UpdateProjectParamsTool implements SupportTool {

    private static final String NOTE_KEY = "support.action.config.note";

    private final ProjectService projects;
    private final SupportActionStore actions;

    public UpdateProjectParamsTool(ProjectService projects, SupportActionStore actions) {
        this.projects = projects;
        this.actions = actions;
    }

    public String name() {
        return "update_project_params";
    }

    public String description() {
        return "Prepare a change to one project's performance/timing parameters, shown as a "
                + "confirmation card. Allowed fields only: batchSize (1-20000), "
                + "fullLoadParallelism (1-8), pollIntervalMs (>=200, polling/full-compare only), "
                + "cdcIdlePollMs (>=50 and <=86400000, only engines that poll a CDC stream: LogMiner, PG "
                + "logical, SQL Server CDC, YStream). The project must be stopped; if it is "
                + "running, prepare a STOP card first. Only projects the caller may edit are "
                + "accepted. This tool does not change strategy, selected tables, sync "
                + "deletes/truncate, cursor columns, cron or application.yml properties — use "
                + "the dedicated tools for cursor columns and cron, and the page for the rest. "
                + "projectId comes from list_projects.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> changeProps = new LinkedHashMap<>();
        changeProps.put("batchSize", Map.of("type", "integer",
                "minimum", 1, "maximum", ProjectTuning.MAX_BATCH_SIZE));
        changeProps.put("fullLoadParallelism", Map.of("type", "integer",
                "minimum", 1, "maximum", ProjectTuning.MAX_PARALLELISM));
        changeProps.put("pollIntervalMs", Map.of("type", "integer",
                "minimum", ProjectTuning.MIN_POLL_INTERVAL_MS,
                "maximum", ProjectTuning.MAX_POLL_INTERVAL_MS));
        changeProps.put("cdcIdlePollMs", Map.of("type", "integer",
                "minimum", ProjectTuning.MIN_CDC_IDLE_POLL_MS,
                "maximum", ProjectTuning.MAX_CDC_IDLE_POLL_MS));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("type", "object");
        changes.put("properties", changeProps);
        changes.put("additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of("type", "integer"));
        properties.put("changes", changes);
        return Map.of("type", "object", "properties", properties,
                "required", List.of("projectId", "changes"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "changes");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long projectId = SupportArgs.requireLong(args, "projectId");
        Map<String, Object> changes = changes(args.path("changes"));

        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return error("error.project.not.found");
        }
        if (!ctx.actor().canEdit(project.getOwnerUsername())) {
            return error("error.accessDenied");
        }
        if (projects.isRunning(project)) {
            // Mirror ProjectService.updateTunables: enabled means "stop it"; stopped with a
            // lease means an old round is finishing, so "stop it" would be wrong advice.
            return error(Boolean.TRUE.equals(project.getEnabled())
                    ? "error.project.config.running" : "error.project.stillStopping");
        }

        SyncConfig config = projects.loadConfig(project);
        List<ProjectTuning.Change> accepted = ProjectTuning.validate(config, project.getTargetKindOrDefault(), changes);

        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ProjectTuning.Change change : accepted) {
            payload.put(change.field().key(), change.after());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", change.field().key());
            row.put("labelKey", change.field().labelKey());
            row.put("before", change.before());
            row.put("beforeIsDefault", change.before() == null);
            row.put("after", change.after());
            row.put("afterIsDefault", false);
            fields.add(row);
        }

        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.PROJECT,
                project.getId(), SupportActionStore.Action.UPDATE_CONFIG, project.getName(),
                payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(
                SupportActionStore.Kind.PROJECT, SupportActionStore.Action.UPDATE_CONFIG));
        out.put("resourceKindKey", "support.action.resource.project");
        out.put("resourceName", project.getName());
        out.put("needsConfirmation", true);
        out.put("fields", fields);
        out.put("noteKey", NOTE_KEY);
        return out;
    }

    private Map<String, Object> changes(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            // Keep the JSON type: ProjectTuning decides "not an allowed field" before "not an
            // integer", so an unknown boolean field is explained as forbidden, not malformed.
            if (value.isIntegralNumber()) {
                out.put(entry.getKey(), value.asLong());
            } else if (value.isFloatingPointNumber()) {
                out.put(entry.getKey(), value.asDouble());
            } else if (value.isBoolean()) {
                out.put(entry.getKey(), value.asBoolean());
            } else if (value.isTextual()) {
                out.put(entry.getKey(), value.asString());
            } else {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
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
