package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;

/**
 * Prepares a start / stop / sync-now for one project. It never calls the service: the returned
 * token becomes a confirmation card, and {@code SupportActionService} re-checks ownership when
 * the user clicks.
 *
 * <p>The ownership rule is applied here too, so an operator who asks the assistant about
 * someone else's project is refused in the conversation instead of being shown a button that
 * is guaranteed to fail.
 */
@Component
public class ControlProjectTool implements SupportTool {

    private static final Set<SupportActionStore.Action> ALLOWED = Set.of(
            SupportActionStore.Action.START, SupportActionStore.Action.STOP,
            SupportActionStore.Action.SYNC_NOW, SupportActionStore.Action.REBASELINE);

    private final ProjectService projects;
    private final SupportActionStore actions;

    public ControlProjectTool(ProjectService projects, SupportActionStore actions) {
        this.projects = projects;
        this.actions = actions;
    }

    public String name() {
        return "control_project";
    }

    public String description() {
        return "Prepare start, stop, a one-off sync, or a CDC baseline rebuild (REBASELINE) "
                + "for one sync project. Saves nothing and runs nothing: returns a token the "
                + "user must confirm on a card. REBASELINE clears the position and runs a full "
                + "load; only suggest it for a parked or expired CDC stream. projectId comes "
                + "from list_projects. Only projects the caller may edit are accepted.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "projectId", Map.of("type", "integer"),
                        "action", Map.of("type", "string",
                                "enum", List.of("START", "STOP", "SYNC_NOW", "REBASELINE"))),
                "required", List.of("projectId", "action"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "action");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long projectId = SupportArgs.requireLong(args, "projectId");
        SupportActionStore.Action action =
                SupportArgs.action(SupportArgs.requireText(args, "action", 16), ALLOWED);

        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return error("error.project.not.found");
        }
        if (!ctx.actor().canEdit(project.getOwnerUsername())) {
            return error("error.accessDenied");
        }

        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.PROJECT,
                project.getId(), action, project.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(SupportActionStore.Kind.PROJECT, action));
        out.put("resourceKindKey", "support.action.resource.project");
        out.put("resourceName", project.getName());
        out.put("needsConfirmation", true);
        return out;
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
