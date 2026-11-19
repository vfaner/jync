package com.qqmu.jync.service.ai.support.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcProbeResult;

import tools.jackson.databind.JsonNode;

/**
 * Runs the CDC capability probe ("自检") against the project's real source and reports each
 * prerequisite — archivelog/wal_level, supplemental logging, retention, mining privileges —
 * with the server's own wording when it fails.
 *
 * <p>This is the one support tool that opens the source database, so it costs a second or two
 * and is capped per conversation. Open to every writer — an operator answers "why is this task
 * not syncing" for all tasks it can see, not only the ones it owns; a viewer (who cannot reach
 * the chat at all) is refused here as defence in depth.
 */
@Component
public class CdcProbeTool implements SupportTool {

    /** Two probe runs per conversation: one to look, one to re-check after a fix. */
    static final int MAX_PROBES = 2;
    private static final int MAX_DETAIL = 300;

    private final CdcOperationsService operations;
    private final ProjectService projects;

    public CdcProbeTool(CdcOperationsService operations, ProjectService projects) {
        this.operations = operations;
        this.projects = projects;
    }

    public String name() {
        return "cdc_probe";
    }

    public String description() {
        return "Run the CDC readiness self-check against one project's source and list every "
                + "item that fails (archivelog, binlog format/retention, supplemental logging, "
                + "replication or mining privileges, server-id collision). Opens the source "
                + "connection and takes a few seconds; at most 2 runs per conversation. "
                + "projectId comes from list_projects. Available to administrators and "
                + "operators for any project.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("projectId", Map.of("type", "integer")),
                "required", List.of("projectId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long projectId = SupportArgs.requireLong(args, "projectId");
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return Map.of("error", "error.project.not.found");
        }
        if (!ctx.actor().canWrite()) {
            return Map.of("error", AccessGuard.DENIED_KEY);
        }
        if (ctx.increment("cdc_probe") > MAX_PROBES) {
            return Map.of("error", "error.support.tooManyQuestions");
        }
        CdcProbeResult result;
        try {
            result = operations.probeProject(projectId);
        } catch (IllegalArgumentException e) {
            // error.project.not.found, or cdc.probe.unsupported for a source with no CDC
            // integration: both are answers the model can explain, not tool failures.
            String key = e.getMessage();
            return Map.of("error", key == null || key.isBlank()
                    ? "error.support.tool.failed" : key);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", result.passed());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (CdcProbeResult.Check check : result.getChecks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", check.getName());
            item.put("passed", check.isPassed());
            item.put("detail", clamp(check.getDetail()));
            checks.add(item);
        }
        out.put("checks", checks);
        return out;
    }

    private String clamp(String detail) {
        return detail == null || detail.length() <= MAX_DETAIL
                ? detail : detail.substring(0, MAX_DETAIL);
    }
}
