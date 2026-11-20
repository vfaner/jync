package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.JsonNode;

/**
 * Prepares enable / disable / run-now / cancel for one dedicated-migration task, with the same
 * two-phase rule as the other control tools: a token for the confirmation card, execution only
 * after a click.
 *
 * <p>Refusals that would dead-end at the click are returned here instead of becoming a card:
 * enabling without a usable cron, a run-now while the task is already running, and a cancel
 * with nothing in flight.
 */
@Component
public class ControlMigrationTool implements SupportTool {

    private static final Set<SupportActionStore.Action> ALLOWED = Set.of(
            SupportActionStore.Action.START, SupportActionStore.Action.STOP,
            SupportActionStore.Action.RUN_NOW, SupportActionStore.Action.CANCEL);

    private final MigrationTaskService tasks;
    private final SupportActionStore actions;

    public ControlMigrationTool(MigrationTaskService tasks, SupportActionStore actions) {
        this.tasks = tasks;
        this.actions = actions;
    }

    public String name() {
        return "control_migration";
    }

    public String description() {
        return "Prepare enable, disable, run-now or cancel for one dedicated-migration task. "
                + "Saves nothing and runs nothing: returns a token the user must confirm on a "
                + "card. Only tasks the caller may edit are accepted; enabling needs a valid "
                + "cron, run-now is refused while a run is in flight, and cancel needs a run to "
                + "cancel. action is one of START, STOP, RUN_NOW, CANCEL; taskId comes from "
                + "list_migrations.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "taskId", Map.of("type", "integer"),
                        "action", Map.of("type", "string",
                                "enum", List.of("START", "STOP", "RUN_NOW", "CANCEL"))),
                "required", List.of("taskId", "action"));
    }

    public Set<String> allowedKeys() {
        return Set.of("taskId", "action");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long taskId = SupportArgs.requireLong(args, "taskId");
        SupportActionStore.Action action =
                SupportArgs.action(SupportArgs.requireText(args, "action", 16), ALLOWED);

        MigrationTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return error("error.migration.not.found");
        }
        if (!ctx.actor().canEdit(task.getOwnerUsername())) {
            return error("error.accessDenied");
        }
        switch (action) {
            case START -> {
                String cron = task.getCronExpression();
                if (cron == null || cron.isBlank()) {
                    return error("error.migration.cron.required");
                }
                if (!CronExpression.isValidExpression(cron.trim())) {
                    return error("error.migration.cron.invalid");
                }
            }
            case RUN_NOW -> {
                if (tasks.isRunning(taskId)) {
                    return error("error.migration.running");
                }
            }
            case CANCEL -> {
                if (!tasks.isRunning(taskId)) {
                    return error("error.migration.not.running");
                }
            }
            default -> {
                // STOP needs no precondition: disabling an already-disabled task is a no-op.
            }
        }

        String token = actions.put(ctx.actor().username(),
                SupportActionStore.Kind.MIGRATION_TASK, task.getId(), action, task.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey",
                SupportActionStore.titleKey(SupportActionStore.Kind.MIGRATION_TASK, action));
        out.put("resourceKindKey", "support.action.resource.migration_task");
        out.put("resourceName", task.getName());
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
