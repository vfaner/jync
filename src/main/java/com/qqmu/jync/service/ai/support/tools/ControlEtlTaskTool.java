package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.etl.EtlTaskService;

import tools.jackson.databind.JsonNode;

/**
 * Prepares an enable / disable / run-now for one ETL task, with the same two-phase rule as
 * {@link ControlProjectTool}: a token for the confirmation card, execution only after a click.
 *
 * <p>A task that belongs to a batch cannot be enabled or disabled on its own — the batch owns
 * the schedule — so the refusal is returned before a dead-end card is shown.
 */
@Component
public class ControlEtlTaskTool implements SupportTool {

    private static final Set<SupportActionStore.Action> ALLOWED = Set.of(
            SupportActionStore.Action.START, SupportActionStore.Action.STOP,
            SupportActionStore.Action.RUN_NOW);

    private final EtlTaskService tasks;
    private final SupportActionStore actions;

    public ControlEtlTaskTool(EtlTaskService tasks, SupportActionStore actions) {
        this.tasks = tasks;
        this.actions = actions;
    }

    public String name() {
        return "control_etl_task";
    }

    public String description() {
        return "Prepare enable, disable or run-now for one ETL task. Saves nothing and runs "
                + "nothing: returns a token the user must confirm on a card. Only tasks the "
                + "caller may edit are accepted; a batch member cannot be enabled or disabled "
                + "individually. taskId comes from etl_status.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "taskId", Map.of("type", "integer"),
                        "action", Map.of("type", "string",
                                "enum", List.of("START", "STOP", "RUN_NOW"))),
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
        SupportActionStore.Action action = parse(SupportArgs.requireText(args, "action", 16));

        EtlTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return error("error.etl.not.found");
        }
        if (!ctx.actor().canEdit(task.getOwnerUsername())) {
            return error("error.accessDenied");
        }
        if (task.getBatchId() != null && (action == SupportActionStore.Action.START
                || action == SupportActionStore.Action.STOP)) {
            return error("error.etl.batch.managed");
        }

        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.ETL_TASK,
                task.getId(), action, task.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey",
                SupportActionStore.titleKey(SupportActionStore.Kind.ETL_TASK, action));
        out.put("resourceKindKey", "support.action.resource.etl_task");
        out.put("resourceName", task.getName());
        out.put("needsConfirmation", true);
        return out;
    }

    private SupportActionStore.Action parse(String raw) {
        try {
            SupportActionStore.Action action = SupportActionStore.Action.valueOf(raw);
            if (!ALLOWED.contains(action)) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            return action;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
