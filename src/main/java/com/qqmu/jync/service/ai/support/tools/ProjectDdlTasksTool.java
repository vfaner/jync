package com.qqmu.jync.service.ai.support.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.sync.SyncStateWriter;

import tools.jackson.databind.JsonNode;

/**
 * Open CDC DDL tasks of one project.
 *
 * <p>{@code cdc_status} answers "is the stream healthy"; this answers "what does the target
 * still owe". A skipped DDL is either waiting for an administrator, already executed but
 * waiting for its row replay, or failed and retryable. The assistant needs the task id, the
 * statement and the gap tables to explain the next action without guessing.
 */
@Component
public class ProjectDdlTasksTool implements SupportTool {

    static final int LIMIT = 20;

    private final ChangeLogRepository changes;
    private final ProjectRepository projects;

    public ProjectDdlTasksTool(ChangeLogRepository changes, ProjectRepository projects) {
        this.changes = changes;
        this.projects = projects;
    }

    public String name() {
        return "project_ddl_tasks";
    }

    public String description() {
        return "List one project's open CDC DDL tasks (up to 20, newest first): pending, "
                + "executed-but-awaiting-replay, or failed retryable. Returns task id, table, "
                + "status, executed/replayed flags, target SQL, error detail and affected "
                + "tables. Read-only; does not execute or replay anything. Use when a project "
                + "shows skipped DDL or target missing-column gaps.";
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
        if (projects.findById(projectId).isEmpty()) {
            return Map.of("error", "error.project.not.found");
        }
        List<ChangeLog> rows = new ArrayList<>();
        rows.addAll(page(projectId, ChangeLog.RESOLUTION_PENDING));
        rows.addAll(page(projectId, ChangeLog.RESOLUTION_FAILED));
        rows.removeIf(row -> row.getDetails() == null
                || !row.getDetails().startsWith(SyncStateWriter.SKIPPED_DDL_PREFIX));
        rows.sort((a, b) -> {
            Instant left = a.getOccurredAt();
            Instant right = b.getOccurredAt();
            return right == null ? -1 : left == null ? 1 : right.compareTo(left);
        });
        if (rows.size() > LIMIT) {
            rows = rows.subList(0, LIMIT);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeLog row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("table", row.getObjectName());
            item.put("at", row.getOccurredAt() == null ? null : row.getOccurredAt().toString());
            item.put("status", row.getResolutionStatus());
            item.put("ddlExecuted", row.getDdlExecuted());
            item.put("replayDone", row.getReplayDone());
            item.put("targetSql", ToolText.safe(row.getTargetDdlSql(), ToolText.MAX_SUMMARY));
            item.put("error", ToolText.safe(row.getErrorDetail(), ToolText.MAX_SUMMARY));
            item.put("affectedTables", row.getAffectedTables());
            item.put("gapEnd", row.getGapEndFile() == null ? null
                    : row.getGapEndFile() + ":" + row.getGapEndPos());
            out.add(item);
        }
        return out;
    }

    private List<ChangeLog> page(long projectId, String status) {
        return changes.findByProjectIdAndResolutionStatusOrderByOccurredAtDesc(
                projectId, status, PageRequest.of(0, LIMIT)).getContent();
    }
}
