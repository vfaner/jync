package com.qqmu.jync.service.ai.support.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call for a reported symptom: "CDC stopped last night", "the reports no longer match",
 * "it keeps failing". It assembles the task state, the active alerts, the recent failures, the
 * CDC position and the switches that decide whether a rebuild can fix a row-count difference.
 *
 * <p>The individual tools remain available for a direct question ("is project 7 running?").
 * This one exists because a diagnosis needs all of them at once, and the model only has four
 * tool calls per round: making it stitch five sources together spends the entire budget before
 * it can reason. Everything here is metadata-only; no source or target database is opened.
 */
@Component
public class DiagnoseProjectTool implements SupportTool {

    static final int DEFAULT_HOURS = 24;
    static final int MAX_HOURS = 168;
    private static final int MAX_FAILURES = 10;

    private static final List<String> CDC_KEYS = List.of("consumerHeldHere", "stats", "position");

    private final ProjectService projects;
    private final ProjectStatusTool status;
    private final CdcStatusTool cdc;
    private final RecentFailuresTool failures;
    private final AlertsTool alerts;
    private final ChangeLogRepository changes;
    private final SyncProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiagnoseProjectTool(ProjectService projects, ProjectStatusTool status,
                               CdcStatusTool cdc, RecentFailuresTool failures,
                               AlertsTool alerts, ChangeLogRepository changes,
                               SyncProperties properties) {
        this.projects = projects;
        this.status = status;
        this.cdc = cdc;
        this.failures = failures;
        this.alerts = alerts;
        this.changes = changes;
        this.properties = properties;
    }

    public String name() {
        return "diagnose_project";
    }

    public String description() {
        return "One-call diagnosis for a reported problem (CDC stalled, data no longer matches, "
                + "repeated failures): task state and progress, active alerts, failed rows from "
                + "the last N hours (default 24, max 168), CDC position and the settings that "
                + "explain a mismatch (syncDeletes, truncateBeforeInitialLoad, compare limits). "
                + "Prefer this over calling project_status, cdc_status, alerts and "
                + "recent_failures one by one. projectId comes from list_projects. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "projectId", Map.of("type", "integer"),
                        "hours", Map.of("type", "integer")),
                "required", List.of("projectId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("projectId", "hours");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long projectId = SupportArgs.requireLong(args, "projectId");
        Long requested = SupportArgs.optionalLong(args, "hours");
        if (requested != null && (requested < 1 || requested > MAX_HOURS)) {
            throw new IllegalArgumentException(SupportArgs.BAD_ARGS);
        }
        int hours = requested == null ? DEFAULT_HOURS : requested.intValue();

        ObjectNode projectArgs = mapper.createObjectNode();
        projectArgs.put("projectId", projectId);

        Map<String, Object> out = new LinkedHashMap<>();
        Object statusResult = status.execute(projectArgs, ctx);
        if (!(statusResult instanceof Map<?, ?> statusMap)) {
            return Map.of("error", "error.support.tool.failed");
        }
        statusMap.forEach((key, value) -> out.put(String.valueOf(key), value));
        if (out.containsKey("error")) {
            return out;
        }

        Project project = projects.findById(projectId).orElse(null);
        if (project != null) {
            out.put("settings", settings(project));
        }
        out.put("liveness", liveness(projectId));

        Object cdcResult = cdc.execute(projectArgs, ctx);
        if (cdcResult instanceof Map<?, ?> cdcMap) {
            Map<String, Object> state = new LinkedHashMap<>();
            for (String key : CDC_KEYS) {
                if (cdcMap.containsKey(key)) {
                    state.put(key, cdcMap.get(key));
                }
            }
            out.put("cdcState", state.isEmpty() ? null : state);
        }

        Object failureResult = failures.execute(failureArgs(projectId, hours), ctx);
        if (failureResult instanceof List<?> list) {
            out.put("recentFailures", list.size() > MAX_FAILURES
                    ? list.subList(0, MAX_FAILURES) : list);
        }

        Object alertResult = alerts.execute(mapper.createObjectNode(), ctx);
        if (alertResult instanceof List<?> list) {
            List<Object> mine = new ArrayList<>();
            for (Object item : list) {
                if (belongsTo(item, projectId)) {
                    mine.add(item);
                }
            }
            out.put("alerts", mine);
        }
        return out;
    }

    private ObjectNode failureArgs(long projectId, int hours) {
        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", projectId);
        args.put("hours", hours);
        return args;
    }

    /**
     * "The source changed and the target did nothing" splits into two questions: did Jync see
     * the change, and did it apply one? The newest row answers the first; the newest successful
     * row answers the second. Both are metadata reads, so this works while the stream is broken.
     */
    private Map<String, Object> liveness(long projectId) {
        ChangeLog latest = changes.findFirstByProjectIdOrderByOccurredAtDesc(projectId)
                .orElse(null);
        ChangeLog lastSuccess =
                changes.findFirstByProjectIdAndSuccessTrueOrderByOccurredAtDesc(projectId)
                        .orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastChangeAt", instant(latest == null ? null : latest.getOccurredAt()));
        out.put("lastChangeSuccess", latest == null ? null : latest.getSuccess());
        out.put("lastChangeType", latest == null || latest.getChangeType() == null
                ? null : latest.getChangeType().name());
        out.put("lastChangeObject", latest == null ? null : latest.getObjectName());
        out.put("lastSuccessAt", instant(lastSuccess == null ? null
                : lastSuccess.getOccurredAt()));
        out.put("minutesSinceLastChange", minutesSince(latest == null ? null
                : latest.getOccurredAt()));
        out.put("minutesSinceLastSuccess", minutesSince(lastSuccess == null ? null
                : lastSuccess.getOccurredAt()));
        return out;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Long minutesSince(Instant value) {
        if (value == null) {
            return null;
        }
        // A clock that moved backwards must not produce a negative age the model reads as fresh.
        return Math.max(0L, Duration.between(value, Instant.now()).toMinutes());
    }

    /**
     * A config that cannot be read is part of the diagnosis, not a reason to fail the whole
     * answer: the state, failures and alerts are still valid evidence.
     */
    private Map<String, Object> settings(Project project) {
        try {
            SyncConfig config = projects.loadConfig(project);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("captureStrategy", config.getCaptureStrategyOrDefault().name());
            out.put("syncData", config.isSyncData());
            out.put("syncStructure", config.isSyncStructure());
            out.put("syncDeletes", config.isSyncDeletes());
            out.put("truncateBeforeInitialLoad", config.isTruncateBeforeInitialLoad());
            out.put("fullCompareMaxRows", properties.getFullCompareMaxRows());
            out.put("deleteCompareMaxRows", properties.getDeleteCompareMaxRows());
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean belongsTo(Object item, long projectId) {
        if (!(item instanceof Map<?, ?> row)) {
            return false;
        }
        Object value = row.get("projectId");
        return value instanceof Number number && number.longValue() == projectId;
    }
}
