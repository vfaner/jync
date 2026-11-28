package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.settings.SystemTuningService;

import tools.jackson.databind.JsonNode;

/**
 * Administrator-only confirmation card for the closed system-tuning whitelist.
 *
 * <p>The values live in {@code system_setting}, so they survive a restart and are shared by
 * every node; a local save applies immediately and other nodes converge on the 30-second
 * refresh. A missing value means "application.yml base", and {@code reset} removes the
 * override.
 */
@Component
public class UpdateSystemParamsTool implements SupportTool {

    private static final String NOTE_KEY = "support.action.system.note";

    private final SystemTuningService tuning;
    private final SupportActionStore actions;

    public UpdateSystemParamsTool(SystemTuningService tuning, SupportActionStore actions) {
        this.tuning = tuning;
        this.actions = actions;
    }

    public String name() {
        return "update_system_params";
    }

    public String description() {
        return "Administrator-only: prepare a confirmation card that overrides a closed set of "
                + "hot system performance parameters in the system_setting table. set maps "
                + "property name to its new value as a string; reset is a list of property "
                + "names whose application.yml value should be restored. Allowed keys only: "
                + "sync.batch-size (1-20000), sync.full-load-batch-size (1-20000), "
                + "sync.fetch-size (1-100000), sync.full-load-fetch-size (1-100000), "
                + "sync.chunk-size (1000-1000000), sync.chunk-target-bytes "
                + "(1048576-1073741824), sync.adaptive-chunk-size (true/false), "
                + "sync.min-adaptive-chunk-size (100-100000), sync.max-retries (0-10), "
                + "sync.fanout.max-targets (2-100, total targets per one-source-many-targets "
                + "project, primary included). "
                + "sync.batch-size takes effect for the next batch of a running CDC consumer on "
                + "this node and the next polling round; other nodes converge within about 30 "
                + "seconds. Other keys apply to subsequent sync rounds. Security, TLS, "
                + "credentials, retention, comparison limits, pool sizes and thread counts are "
                + "not editable. Nothing is written until Confirm.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "object");
        set.put("additionalProperties", Map.of("type", "string"));
        set.put("description", "Property name to new value, as a string.");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("set", set);
        properties.put("reset", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "Property names to restore to their application.yml value."));
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("set", "reset");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().isAdmin()) {
            return error("error.accessDenied");
        }
        Map<String, String> set = set(args.path("set"));
        List<String> reset = reset(args.path("reset"));
        List<SystemTuningService.Change> changes = tuning.preview(set, reset);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (SystemTuningService.Change change : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", change.key());
            // The property name is its own label; the UI lookup falls back to the key.
            row.put("labelKey", change.key());
            row.put("before", change.before());
            // Always show the real base/effective number; "default" in the UI means "not
            // overridden", and hiding 500 behind that label would make the card useless.
            row.put("beforeIsDefault", false);
            row.put("after", change.after());
            row.put("afterIsDefault", change.afterIsDefault());
            fields.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("set", set);
        payload.put("reset", reset);
        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.SYSTEM,
                0L, SupportActionStore.Action.UPDATE_PARAMS, "system", payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(SupportActionStore.Kind.SYSTEM,
                SupportActionStore.Action.UPDATE_PARAMS));
        out.put("resourceKindKey", "support.action.resource.system");
        out.put("resourceName", "system");
        out.put("needsConfirmation", true);
        out.put("fields", fields);
        out.put("noteKey", NOTE_KEY);
        return out;
    }

    private Map<String, String> set(JsonNode node) {
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
            out.put(entry.getKey(), entry.getValue().asString(""));
        }
        return out;
    }

    private List<String> reset(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
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
