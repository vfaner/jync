package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import tools.jackson.databind.JsonNode;

@Component
public class ListProjectsTool implements SupportTool {

    private static final int MAX_ROWS = 50;

    private final ProjectRepository projects;
    private final ProjectService projectService;

    public ListProjectsTool(ProjectRepository projects, ProjectService projectService) {
        this.projects = projects;
        this.projectService = projectService;
    }

    public String name() {
        return "list_projects";
    }

    public String description() {
        return "List sync projects, optionally filtered by a name substring.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of());
    }

    public Set<String> allowedKeys() {
        return Set.of("query");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        String query = SupportArgs.optionalText(args, "query");
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<Project> sorted = new ArrayList<>(projects.findAll());
        sorted.sort(Comparator.comparing(Project::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project project : sorted) {
            if (needle != null && (project.getName() == null
                    || !project.getName().toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            if (out.size() >= MAX_ROWS) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", project.getId());
            row.put("name", project.getName());
            row.put("running", projectService.isRunning(project));
            row.put("targetKind", project.getTargetKindOrDefault().name());
            row.put("owner", project.getOwnerUsername());
            out.add(row);
        }
        return out;
    }
}
