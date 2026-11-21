package com.qqmu.jync.service.ai.support.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;

import tools.jackson.databind.JsonNode;

/**
 * The CDC half of "why is this task not moving": the stored strategy, whether THIS node holds
 * the consumer, the consumer's own last error and skip counters, and the persisted position.
 *
 * <p>Reads in-memory state and the metadata store only — never the source database, so it is
 * safe to call on a broken stream. A null {@code statsFor} means another node holds the
 * consumer (or none is running); the persisted position still answers where the stream stands.
 */
@Component
public class CdcStatusTool implements SupportTool {

    /** A GTID set can be thousands of characters; the model needs its shape, not all of it. */
    private static final int MAX_TEXT = 300;

    private final ProjectService projects;
    private final CdcConsumerManager consumers;
    private final CdcPositionStore positions;

    public CdcStatusTool(ProjectService projects, CdcConsumerManager consumers,
                         CdcPositionStore positions) {
        this.projects = projects;
        this.consumers = consumers;
        this.positions = positions;
    }

    public String name() {
        return "cdc_status";
    }

    public String description() {
        return "Read one project's log-CDC state: capture strategy, whether the consumer runs "
                + "on this node, its connection, last error, position and skip counters "
                + "(skipped DDL, pruned columns, missing LOBs). projectId comes from "
                + "list_projects. Read-only and cheap.";
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
        CaptureStrategy strategy = projects.loadConfig(project).getCaptureStrategyOrDefault();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", project.getName());
        out.put("strategy", strategy.name());
        out.put("cdc", strategy.isLogCdc());
        if (!strategy.isLogCdc()) {
            return out;
        }

        CdcRuntimeStats stats = consumers.statsFor(projectId);
        out.put("consumerHeldHere", stats != null);
        if (stats != null) {
            out.put("stats", textify(stats.snapshot()));
        }

        CdcPosition position = positions.find(projectId).orElse(null);
        if (position != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("binlogFile", position.getBinlogFile());
            p.put("binlogPos", position.getBinlogPos());
            p.put("gtidSet", clamp(position.getGtidSet()));
            p.put("scn", position.getScn());
            p.put("lsn", position.getLsn());
            p.put("baselineDone", position.isBaselineDone());
            p.put("updatedAt", instant(position.getUpdatedAt()));
            out.put("position", p);
        }
        return out;
    }

    /**
     * Instants become ISO strings; the registry's mapper must not guess a date format.
     * Free text (the consumer's last error) is masked and capped before it leaves the
     * process — a driver error can carry a JDBC URL or a password assignment.
     */
    private Map<String, Object> textify(Map<String, Object> snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        snapshot.forEach((key, value) -> out.put(key,
                value instanceof Instant instant ? instant.toString()
                        : value instanceof String text
                                ? ToolText.safe(text, ToolText.MAX_SUMMARY) : value));
        return out;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String clamp(String value) {
        return value == null || value.length() <= MAX_TEXT
                ? value : value.substring(0, MAX_TEXT);
    }
}
