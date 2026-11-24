package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.connection.ConnectionParams;

import tools.jackson.databind.JsonNode;

/**
 * Read side for one connection: product, role, owner, the parsed JDBC extra parameters and
 * which sync tasks use it. It exists so a recommendation about a driver parameter can start
 * from the current value and from the blast radius (a connection may be shared by several
 * tasks), not from a guess.
 *
 * <p>Metadata only — host, port, account, password, URL and driver jar are never returned.
 */
@Component
public class ConnectionDetailsTool implements SupportTool {

    private static final int MAX_PROJECTS = 20;
    private static final int MAX_PARAMS = 50;

    private final DatabaseConfigService databases;

    public ConnectionDetailsTool(DatabaseConfigService databases) {
        this.databases = databases;
    }

    public String name() {
        return "connection_details";
    }

    public String description() {
        return "Read one connection's product, role, owner, current JDBC extra parameters "
                + "(parsed key to value) and the sync tasks using it. Use it before proposing "
                + "a driver-parameter change, so the current value and the number of affected "
                + "tasks are known. Does not return host, account, password, URL or driver jar. "
                + "connectionId comes from list_connections. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("connectionId", Map.of("type", "integer")),
                "required", List.of("connectionId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("connectionId");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long connectionId = SupportArgs.requireLong(args, "connectionId");
        DatabaseConfig connection = databases.findById(connectionId).orElse(null);
        if (connection == null) {
            return Map.of("error", "error.connection.not.found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", connection.getName());
        out.put("role", connection.getRole() == null ? null : connection.getRole().name());
        out.put("type", connection.getType() == null ? null : connection.getType().name());
        out.put("effectiveType", connection.effectiveType().name());
        out.put("owner", connection.getOwnerUsername());
        Map<String, String> params = ConnectionParams.redacted(
                ConnectionParams.parse(connection.getExtraParams()));
        if (params.size() > MAX_PARAMS) {
            Map<String, String> sample = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (sample.size() >= MAX_PARAMS) {
                    break;
                }
                sample.put(entry.getKey(), entry.getValue());
            }
            out.put("extraParams", sample);
            out.put("extraParamsTruncated", true);
        } else {
            out.put("extraParams", params);
        }

        List<Project> using = databases.projectsUsing(connectionId);
        List<String> names = new ArrayList<>();
        List<String> running = new ArrayList<>();
        for (Project project : using) {
            if (names.size() < MAX_PROJECTS) {
                names.add(project.getName());
            }
            if (Boolean.TRUE.equals(project.getEnabled())
                    && running.size() < MAX_PROJECTS) {
                running.add(project.getName());
            }
        }
        out.put("projectsUsing", names);
        out.put("projectsUsingCount", using.size());
        out.put("runningProjects", running);
        out.put("truncatedProjects", using.size() > names.size());
        return out;
    }
}
