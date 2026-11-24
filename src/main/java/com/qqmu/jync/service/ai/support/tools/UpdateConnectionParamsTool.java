package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportActionStore;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.connection.ConnectionParams;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Prepares a change to one connection's free-form JDBC {@code extraParams} — timeouts,
 * batching flags, keep-alive and similar driver settings.
 *
 * <p>Only the parameter string is editable: host, port, database, accounts, password, custom
 * URL and driver jar stay on the connection page. A connection may be shared by several
 * tasks, so the tool lists the affected tasks and the existing service refuses the save
 * while any of them is running (the pool is shared and evicted on save).
 */
@Component
public class UpdateConnectionParamsTool implements SupportTool {

    static final int MAX_CHANGES = 20;
    private static final String NOTE_KEY = "support.action.connection.note";
    private static final int MAX_CARD_CHARS = 6000;

    private final DatabaseConfigService databases;
    private final SupportActionStore actions;
    private final ObjectMapper mapper = new ObjectMapper();

    public UpdateConnectionParamsTool(DatabaseConfigService databases,
                                      SupportActionStore actions) {
        this.databases = databases;
        this.actions = actions;
    }

    public String name() {
        return "update_connection_params";
    }

    public String description() {
        return "Prepare a change to one connection's JDBC extra parameters (driver timeouts, "
                + "batching/keep-alive flags, etc.), shown as a confirmation card. set maps "
                + "parameter name to value; remove is a list of names to delete; at least one "
                + "is required and together they may carry at most 20 changes. Only the "
                + "extra-parameter string can be changed — never host, port, database, account, "
                + "password, custom URL or driver jar. The caller must be able to edit the "
                + "connection, and no task using it may be running. The card lists old and new "
                + "values; the change affects every task using this connection. connectionId "
                + "comes from list_connections; inspect current values with connection_details.";
    }

    public Map<String, Object> parameters() {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("type", "object");
        set.put("description", "Parameter name to value.");
        set.put("additionalProperties", Map.of("type", "string"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("connectionId", Map.of("type", "integer"));
        properties.put("set", set);
        properties.put("remove", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "Parameter names to delete."));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("connectionId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("connectionId", "set", "remove");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        if (!ctx.actor().canWrite()) {
            return error("error.accessDenied");
        }
        long connectionId = SupportArgs.requireLong(args, "connectionId");
        Map<String, String> set = new LinkedHashMap<>();
        List<String> remove = new ArrayList<>();
        parseSet(args.path("set"), set);
        parseRemove(args.path("remove"), remove);
        if (set.isEmpty() && remove.isEmpty()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        if (set.size() + remove.size() > MAX_CHANGES) {
            return error("error.support.param.tooMany");
        }

        DatabaseConfig connection = databases.findById(connectionId).orElse(null);
        if (connection == null) {
            return error("error.connection.not.found");
        }
        if (!ctx.actor().canEdit(connection.getOwnerUsername())) {
            return error("error.accessDenied");
        }
        List<Project> using = databases.projectsUsing(connectionId);
        List<String> running = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        for (Project project : using) {
            if (databases.isRunning(project) && running.size() < 10) {
                running.add(project.getName());
            }
            if (!ctx.actor().isAdmin() && !ctx.actor().canEdit(project.getOwnerUsername())
                    && foreign.size() < 10) {
                foreign.add(project.getName());
            }
        }
        if (!foreign.isEmpty()) {
            Map<String, Object> out = error("error.connection.sharedByOthers");
            out.put("foreignProjects", foreign);
            return out;
        }
        if (!running.isEmpty()) {
            Map<String, Object> out = error("error.connection.edit.running");
            out.put("runningProjects", running);
            return out;
        }

        String before = connection.getExtraParams();
        String after = ConnectionParams.merge(before, set, remove);
        Map<String, String> oldValues = ConnectionParams.parse(before);
        Map<String, String> newValues = ConnectionParams.parse(after);
        if (ConnectionParams.normalized(oldValues)
                .equals(ConnectionParams.normalized(newValues))) {
            return error("error.support.param.unchanged");
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String key : changedKeys(set, remove)) {
            String oldValue = ConnectionParams.lookup(oldValues, key);
            String newValue = ConnectionParams.lookup(newValues, key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", "param:" + key);
            // A driver parameter name is its own display label; the UI lookup falls back to
            // the key when no translation exists.
            row.put("labelKey", key);
            row.put("before", oldValue == null ? "" : oldValue);
            row.put("beforeIsDefault", oldValue == null);
            row.put("after", newValue == null ? "" : newValue);
            row.put("afterIsDefault", newValue == null);
            fields.add(row);
        }
        if (fields.isEmpty()) {
            return error("error.support.param.unchanged");
        }
        List<String> affected = using.stream().map(Project::getName).limit(20).toList();
        if (tooLarge(Map.of("fields", fields, "affectedProjects", affected))) {
            return error("error.support.param.tooMany");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("set", set);
        payload.put("remove", remove);
        String token = actions.put(ctx.actor().username(), SupportActionStore.Kind.CONNECTION,
                connection.getId(), SupportActionStore.Action.UPDATE_PARAMS, connection.getName(),
                payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("titleKey", SupportActionStore.titleKey(SupportActionStore.Kind.CONNECTION,
                SupportActionStore.Action.UPDATE_PARAMS));
        out.put("resourceKindKey", "support.action.resource.connection");
        out.put("resourceName", connection.getName());
        out.put("needsConfirmation", true);
        out.put("fields", fields);
        out.put("noteKey", NOTE_KEY);
        out.put("affectedProjects", affected);
        return out;
    }

    private void parseSet(JsonNode node, Map<String, String> set) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        for (var entry : node.properties()) {
            String key = key(entry.getKey());
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()
                    || value.asString("").length() > 1024) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            set.put(key, value.asString(""));
        }
    }

    private void parseRemove(JsonNode node, List<String> remove) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
            }
            remove.add(key(item.asString("")));
        }
    }

    private String key(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        if (ConnectionParams.isSensitiveKey(key)) {
            // Account/password/token overrides are not the assistant's to touch.
            throw new IllegalArgumentException("error.support.param.notAllowed");
        }
        return key;
    }

    private boolean tooLarge(Object value) {
        try {
            return mapper.writeValueAsString(value).length() > MAX_CARD_CHARS;
        } catch (Exception e) {
            return true;
        }
    }

    private List<String> changedKeys(Map<String, String> set, List<String> remove) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(set.keySet());
        keys.addAll(remove);
        return new ArrayList<>(keys);
    }

    private Map<String, Object> error(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", key);
        return out;
    }
}
