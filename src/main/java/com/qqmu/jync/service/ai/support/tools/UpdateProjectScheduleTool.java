package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Sets or clears one project's cron expression. A cron wins over the poll interval in the
 * scheduler; clearing it returns the project to interval scheduling with the configured (or
 * global) interval. Log CDC runs on a long-lived consumer and a Redis target has no Quartz
 * job, so both are refused rather than given a dead schedule.
 */
@Component
public class UpdateProjectScheduleTool implements SupportTool {

    private static final String NOTE_KEY = "support.action.schedule.note";

    private final ProjectService projects;
    private final SupportActionStore actions;

    public UpdateProjectScheduleTool(ProjectService projects, SupportActionStore actions) {
        this.projects = projects;
        this.actions = actions;
    }

    public String name() {
        return "update_project_schedule";
    }

    public String description() {
        return "Prepare a change to one project's cron expression, shown as a confirmation "
                + "card. A blank cronExpression clears the cron and returns to the interval "
                + "schedule; a cron takes precedence over the poll interval. Only polling / "
                + "full-compare projects are accepted (log CDC and Redis targets have no "
                + "Quartz schedule). The project must be stopped, and only projects the "
                + "caller may edit are accepted. projectId comes from list_projects.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of("type", "integer"));
        properties.put("cronExpression", Map.of("type", "string",
                "description", "Quartz cron, e.g. 0 0/5 * * * ?; empty clears it."));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("projectId", "cronExpression"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "cronExpression");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long projectId = SupportArgs.requireLong(args, "projectId");
        JsonNode cronNode = args.path("cronExpression");
        if (!cronNode.isTextual() || cronNode.asString("").length() > 128) {
            // Blank is meaningful (clear the cron), so SupportArgs.requireText cannot be used:
            // it turns blank into a missing argument.
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        String raw = cronNode.asString("");

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
        ProjectTuning.requireScheduleApplicable(config, project.getTargetKindOrDefault());
        String cron = ProjectTuning.normalizeCron(raw);
        String previous = config.getCronExpression() == null
                || config.getCronExpression().isBlank()
                ? null : config.getCronExpression().trim();
        if (Objects.equals(previous, cron)) {
            return error("error.support.param.unchanged");
        }

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", "cronExpression");
        field.put("labelKey", "option.cronExpression");
        field.put("before", previous == null ? "" : previous);
        field.put("beforeIsDefault", previous == null);
        field.put("after", cron == null ? "" : cron);
        field.put("afterIsDefault", cron == null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cronExpression", cron == null ? "" : cron);
        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.PROJECT,
                project.getId(), SupportActionStore.Action.UPDATE_SCHEDULE, project.getName(),
                payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(
                SupportActionStore.Kind.PROJECT, SupportActionStore.Action.UPDATE_SCHEDULE));
        out.put("resourceKindKey", "support.action.resource.project");
        out.put("resourceName", project.getName());
        out.put("needsConfirmation", true);
        out.put("fields", List.of(field));
        out.put("noteKey", NOTE_KEY);
        return out;
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
