package com.qqmu.jync.service.ai.support.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.Ownership;
import tools.jackson.databind.JsonNode;

/**
 * Exact {@code COUNT(*)} on both sides of one table.
 *
 * <p>The most expensive tool by far, so it is bounded twice: at most three scans per
 * conversation, and a repeated question inside a minute reuses the previous answer instead of
 * scanning again. Because a cache hit costs nothing, it does not consume the scan quota —
 * the quota counts scans, not questions.
 */
@Component
public class ProjectRowCountTool implements SupportTool {

    static final int MAX_SCANS_PER_CONVERSATION = 3;
    static final long CACHE_TTL_MS = 60_000L;
    /** Entries are cheap; the cap only stops an unbounded map in a months-long process. */
    static final int MAX_CACHE_ENTRIES = 512;

    private record Cached(TableRowCount count, long at) {
    }

    private final TablePreviewService preview;
    private final ProjectRepository projects;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ProjectRowCountTool(TablePreviewService preview, ProjectRepository projects) {
        this.preview = preview;
        this.projects = projects;
    }

    public String name() {
        return "project_row_count";
    }

    public String description() {
        return "Compare the exact source and target row counts of one table in a project. "
                + "Expensive: at most three calls per conversation. Use only when the user "
                + "asks whether the two sides match.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "projectId", Map.of("type", "integer"),
                        "table", Map.of("type", "string")),
                "required", List.of("projectId", "table"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "table");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long projectId = SupportArgs.requireLong(args, "projectId");
        String table = SupportArgs.requireText(args, "table", 128);
        // The row-count page requires the same edit right, and the check must run before the
        // cache: a scan someone else paid for must not answer here either. A missing project
        // keeps its existing not-found error.
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            return Map.of("error", "error.project.not.found");
        }
        try {
            Ownership.requireReferenceable(project.getOwnerUsername(), project.getName(),
                    ctx.actor());
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
        String key = projectId + ":" + table;
        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < CACHE_TTL_MS) {
            return body(hit.count(), true, (now - hit.at()) / 1000);
        }
        if (ctx.count("rowCount") >= MAX_SCANS_PER_CONVERSATION) {
            return Map.of("error", "error.support.tooManyQuestions");
        }
        ctx.increment("rowCount");
        TableRowCount count = preview.rowCount(projectId, table);
        cache.put(key, new Cached(count, now));
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.values().removeIf(entry -> now - entry.at() >= CACHE_TTL_MS);
        }
        return body(count, false, 0);
    }

    private Map<String, Object> body(TableRowCount count, boolean cached, long ageSeconds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", count.table());
        out.put("source", side(count.source()));
        out.put("target", side(count.target()));
        out.put("elapsedMs", count.elapsedMs());
        out.put("cached", cached);
        out.put("ageSeconds", ageSeconds);
        return out;
    }

    private Map<String, Object> side(TableRowCount.SideCount side) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", side.status().name());
        out.put("count", side.count());
        out.put("elapsedMs", side.elapsedMs());
        return out;
    }
}
