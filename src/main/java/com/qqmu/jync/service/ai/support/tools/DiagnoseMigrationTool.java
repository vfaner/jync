package com.qqmu.jync.service.ai.support.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.JsonNode;

/**
 * One call for "the dedicated migration has not moved": schedule and enabled state, the two
 * endpoints by name and kind, whether a run is in flight, and the bounded run history with each
 * attempt's index and document counts.
 *
 * <p>The in-flight row is the answer to "is it stalled?" — a RUNNING run that reports the same
 * {@code currentIndex} across attempts while {@code documentsWritten} stays put is a different
 * problem from a schedule that never fired. What stays local: endpoint addresses, credentials
 * and the opaque options JSON.
 */
@Component
public class DiagnoseMigrationTool implements SupportTool {

    private static final int MAX_RESULT_DETAIL = 300;
    private static final int MAX_RUNS = 20;

    private final MigrationTaskRepository tasks;
    private final MigrationRunRepository runs;
    private final MigrationConnectionRepository connections;
    private final MigrationTaskService migrationTasks;

    public DiagnoseMigrationTool(MigrationTaskRepository tasks, MigrationRunRepository runs,
                                 MigrationConnectionRepository connections,
                                 MigrationTaskService migrationTasks) {
        this.tasks = tasks;
        this.runs = runs;
        this.connections = connections;
        this.migrationTasks = migrationTasks;
    }

    public String name() {
        return "diagnose_migration";
    }

    public String description() {
        return "One-call diagnosis for a dedicated-migration task that is not making progress: "
                + "type, enabled state, cron and whether it is valid, source/target endpoint "
                + "names and kinds, whether a run is in flight, latest result line, and the last "
                + "20 runs with status, index and document counts, current index and message. "
                + "taskId comes from list_migrations. Cheap.";
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("taskId", Map.of("type", "integer")),
                "required", List.of("taskId"));
    }

    public Set<String> allowedKeys() {
        return Set.of("taskId");
    }

    public Object execute(JsonNode args, ToolContext ctx) {
        long taskId = SupportArgs.requireLong(args, "taskId");
        MigrationTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return Map.of("error", "error.migration.not.found");
        }
        List<MigrationRun> history = runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(taskId);
        if (history == null) {
            history = List.of();
        }
        if (history.size() > MAX_RUNS) {
            history = history.subList(0, MAX_RUNS);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("name", task.getName());
        out.put("type", task.getType() == null ? null : task.getType().name());
        out.put("enabled", task.getEnabled());
        out.put("cron", task.getCronExpression());
        out.put("cronValid", cronValid(task.getCronExpression()));
        out.put("source", connection(task.getSourceConnectionId()));
        out.put("target", connection(task.getTargetConnectionId()));
        out.put("running", isRunning(taskId));
        out.put("lastRunTime", instant(task.getLastRunTime()));
        out.put("lastRunSuccess", task.getLastRunSuccess());
        out.put("lastRunResult", clamp(task.getLastRunResult(), MAX_RESULT_DETAIL));
        out.put("minutesSinceLastRun", minutesSince(task.getLastRunTime()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MigrationRun run : history) {
            rows.add(runRow(run));
        }
        out.put("recentRuns", rows);
        out.put("consecutiveFailures", consecutiveFailures(task, history));

        Instant lastSuccessAt = lastSuccess(task, history);
        out.put("lastSuccessAt", instant(lastSuccessAt));
        out.put("minutesSinceLastSuccess", minutesSince(lastSuccessAt));
        return out;
    }

    private Map<String, Object> runRow(MigrationRun run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("status", run.getStatus() == null ? null : run.getStatus().name());
        row.put("trigger", run.getTriggerType() == null ? null : run.getTriggerType().name());
        row.put("startedAt", instant(run.getStartedAt()));
        row.put("finishedAt", instant(run.getFinishedAt()));
        row.put("indicesTotal", run.getIndicesTotal());
        row.put("indicesSucceeded", run.getIndicesSucceeded());
        row.put("indicesFailed", run.getIndicesFailed());
        row.put("documentsRead", run.getDocumentsRead());
        row.put("documentsWritten", run.getDocumentsWritten());
        row.put("currentIndex", run.getCurrentIndex());
        row.put("message", clamp(run.getMessage(), MAX_RESULT_DETAIL));
        return row;
    }

    private Map<String, Object> connection(Long id) {
        if (id == null) {
            return null;
        }
        MigrationConnection connection = connections.findById(id).orElse(null);
        if (connection == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", connection.getId());
        out.put("name", connection.getName());
        out.put("kind", connection.getKind() == null ? null : connection.getKind().name());
        return out;
    }

    private Boolean cronValid(String cron) {
        if (cron == null || cron.isBlank()) {
            return null;
        }
        return CronExpression.isValidExpression(cron.trim());
    }

    private boolean isRunning(long taskId) {
        try {
            return migrationTasks.isRunning(taskId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * How many of the newest completed runs in a row failed.
     *
     * <p>An in-flight run is not an outcome and is skipped; a cancellation is deliberate, so it
     * ends the streak without being counted as a failure. With no history rows the task row's
     * last outcome is the only evidence there is.
     */
    private int consecutiveFailures(MigrationTask task, List<MigrationRun> history) {
        if (history.isEmpty()) {
            return Boolean.FALSE.equals(task.getLastRunSuccess()) ? 1 : 0;
        }
        int count = 0;
        for (MigrationRun run : history) {
            MigrationRunStatus status = run.getStatus();
            if (status == MigrationRunStatus.RUNNING) {
                continue;
            }
            if (status == MigrationRunStatus.FAILED
                    || status == MigrationRunStatus.INTERRUPTED) {
                count++;
                continue;
            }
            break;
        }
        return count;
    }

    private Instant lastSuccess(MigrationTask task, List<MigrationRun> history) {
        for (MigrationRun run : history) {
            if (run.getStatus() == MigrationRunStatus.SUCCESS && run.getStartedAt() != null) {
                return run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt();
            }
        }
        return Boolean.TRUE.equals(task.getLastRunSuccess()) ? task.getLastRunTime() : null;
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Long minutesSince(Instant value) {
        if (value == null) {
            return null;
        }
        return Math.max(0L, Duration.between(value, Instant.now()).toMinutes());
    }

    private String clamp(String value, int max) {
        return ToolText.safe(value, max);
    }
}
