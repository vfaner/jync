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
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.fanout.FanoutPositionStore;
import com.qqmu.jync.service.fanout.FanoutRuntime;
import com.qqmu.jync.service.fanout.FanoutRuntimeRegistry;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class CdcStatusTool implements SupportTool {

    /** A GTID set can be thousands of characters; the model needs its shape, not all of it. */
    private static final int MAX_TEXT = 300;

    private final ProjectService projects;
    private final CdcConsumerManager consumers;
    private final CdcPositionStore positions;
    private final FanoutPositionStore fanoutPositions;
    private final FanoutRuntimeRegistry fanoutRuntimes;
    private final DatabaseConfigService databases;

    public CdcStatusTool(ProjectService projects, CdcConsumerManager consumers,
                         CdcPositionStore positions) {
        this(projects, consumers, positions, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CdcStatusTool(ProjectService projects, CdcConsumerManager consumers,
                         CdcPositionStore positions, FanoutPositionStore fanoutPositions,
                         FanoutRuntimeRegistry fanoutRuntimes, DatabaseConfigService databases) {
        this.projects = projects;
        this.consumers = consumers;
        this.positions = positions;
        this.fanoutPositions = fanoutPositions;
        this.fanoutRuntimes = fanoutRuntimes;
        this.databases = databases;
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
        com.qqmu.jync.dto.SyncConfig config = projects.loadConfig(project);
        CaptureStrategy strategy = config.getCaptureStrategyOrDefault();
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
            p.put("externalBaseline", position.hasExternalBaseline());
            p.put("externalBaselineTables", position.getExternalBaselineTables());
            p.put("updatedAt", instant(position.getUpdatedAt()));
            out.put("position", p);
        }
        addFanout(project, config, out);
        return out;
    }

    /** Per-target fan-out positions and live worker errors; the project anchor is shared. */
    private void addFanout(Project project, com.qqmu.jync.dto.SyncConfig config,
                           Map<String, Object> out) {
        if (fanoutPositions == null || config == null
                || config.getFanoutTargetDbIds() == null
                || config.getFanoutTargetDbIds().isEmpty()) {
            return;
        }
        Map<Long, com.qqmu.jync.model.FanoutPosition> byTarget = new LinkedHashMap<>();
        try {
            for (com.qqmu.jync.model.FanoutPosition p
                    : fanoutPositions.list(project.getId())) {
                byTarget.put(p.getTargetDbId(), p);
            }
        } catch (RuntimeException e) {
            log.debug("Could not read fan-out positions: {}", e.toString());
        }
        Map<Long, FanoutRuntime.TargetState> states = new LinkedHashMap<>();
        FanoutRuntime runtime = fanoutRuntimes == null ? null
                : fanoutRuntimes.get(project.getId());
        if (runtime != null) {
            for (FanoutRuntime.TargetState state : runtime.workerStates()) {
                states.put(state.targetDbId(), state);
            }
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (project.getTargetDbId() != null) {
            ids.add(project.getTargetDbId());
        }
        ids.addAll(config.getFanoutTargetDbIds());
        java.util.List<Map<String, Object>> targets = new java.util.ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("targetDbId", id);
            target.put("primary", id.equals(project.getTargetDbId()));
            if (databases != null) {
                databases.findById(id).ifPresent(db -> {
                    target.put("name", db.getName());
                    target.put("type", db.effectiveType().name());
                });
            }
            com.qqmu.jync.model.FanoutPosition p = byTarget.get(id);
            if (p != null) {
                target.put("binlogFile", p.getBinlogFile());
                target.put("binlogPos", p.getBinlogPos());
                target.put("lsn", p.getLsn());
                target.put("scn", p.getScn());
                target.put("updatedAt", instant(p.getUpdatedAt()));
            }
            FanoutRuntime.TargetState state = states.get(id);
            if (state != null) {
                target.put("workerAlive", state.alive());
                target.put("workerError", ToolText.safe(state.lastError() == null
                        ? null : state.lastError(), ToolText.MAX_SUMMARY));
                target.put("appliedTransactions", state.appliedTransactions());
                target.put("lastAppliedPosition", state.lastAppliedPosition());
            }
            targets.add(target);
        }
        Map<String, Object> fanout = new LinkedHashMap<>();
        fanout.put("transport", config.getFanoutTransport() == null
                || config.getFanoutTransport().isBlank() ? "SPOOL"
                        : config.getFanoutTransport());
        fanout.put("targetCount", targets.size());
        fanout.put("targets", targets);
        fanout.put("note", "project position is the shared source anchor; per-target "
                + "positions above are independent");
        out.put("fanout", fanout);
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
        return ToolText.safe(value, MAX_TEXT);
    }
}
