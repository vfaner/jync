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

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.EtlRun;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.SupportTool;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.etl.EtlCheckpointStore;
import com.qqmu.jync.service.etl.EtlTaskService;

import tools.jackson.databind.JsonNode;

/**
 * One call for "the ETL task stopped bringing data": schedule and enabled state, the connections
 * it reads and writes by name, whether a run is in flight, the latest result sentence and the
 * bounded run history that separates "fired on schedule with nothing new" from "has been failing
 * since midnight".
 *
 * <p>What it deliberately does not send: the extraction SQL, the range condition, the variable
 * definitions, the checkpoint value and anything from the source or target rows. The history's
 * row counts and timestamps answer the liveness question without carrying business data.
 */
@Component
public class DiagnoseEtlTaskTool implements SupportTool {

    private static final int MAX_RESULT_DETAIL = 300;
    private static final int MAX_RUNS = 20;

    private final EtlTaskRepository tasks;
    private final EtlRunRepository runs;
    private final DatabaseConfigService configs;
    private final EtlTaskService etlTasks;
    private final EtlCheckpointStore checkpoints;

    public DiagnoseEtlTaskTool(EtlTaskRepository tasks, EtlRunRepository runs,
                               DatabaseConfigService configs, EtlTaskService etlTasks,
                               EtlCheckpointStore checkpoints) {
        this.tasks = tasks;
        this.runs = runs;
        this.configs = configs;
        this.etlTasks = etlTasks;
        this.checkpoints = checkpoints;
    }

    public String name() {
        return "diagnose_etl_task";
    }

    public String description() {
        return "One-call diagnosis for an ETL task that is not bringing new data: enabled state, "
                + "cron and whether it is valid, source/target connection names, whether a run "
                + "is in flight, latest result line, and the last 20 runs with their row counts "
                + "and timestamps (so an empty recent run vs repeated failures is visible). "
                + "taskId comes from etl_status. Prefer this over calling etl_status plus "
                + "guesswork. Cheap.";
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
        EtlTask task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return Map.of("error", "error.etl.not.found");
        }
        List<EtlRun> history = runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(taskId);
        if (history == null) {
            history = List.of();
        }
        if (history.size() > MAX_RUNS) {
            history = history.subList(0, MAX_RUNS);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("name", task.getName());
        out.put("enabled", task.getEnabled());
        out.put("cron", task.getCronExpression());
        out.put("cronValid", cronValid(task.getCronExpression()));
        out.put("batchId", task.getBatchId());
        out.put("writeMode", task.getWriteMode() == null ? null : task.getWriteMode().name());
        out.put("targetTable", task.getTargetTable());
        out.put("source", connection(task.getSourceDbId()));
        out.put("target", connection(task.getTargetDbId()));
        out.put("running", isRunning(taskId));
        out.put("checkpointPresent", checkpointPresent(taskId));
        out.put("runIndex", task.getRunIndex());
        out.put("lastRunTime", instant(task.getLastRunTime()));
        out.put("lastRunSuccess", task.getLastRunSuccess());
        out.put("lastRunResult", clamp(task.getLastRunResult(), MAX_RESULT_DETAIL));
        out.put("minutesSinceLastRun", minutesSince(task.getLastRunTime()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (EtlRun run : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("success", run.getSuccess());
            row.put("affectedRows", run.getAffectedRows());
            row.put("occurredAt", instant(run.getOccurredAt()));
            rows.add(row);
        }
        out.put("recentRuns", rows);
        out.put("consecutiveFailures", consecutiveFailures(task, history));

        Instant lastSuccessAt = lastSuccess(task, history);
        out.put("lastSuccessAt", instant(lastSuccessAt));
        out.put("minutesSinceLastSuccess", minutesSince(lastSuccessAt));

        // The text itself stays local; its presence is what the diagnosis needs to know.
        out.put("hasRangeCondition", task.getRangeCondition() != null);
        out.put("hasResumeKey", task.getResumeKeyColumn() != null);
        out.put("hasUpsertKeys", task.getUpsertKeyColumns() != null);
        return out;
    }

    private Map<String, Object> connection(Long id) {
        if (id == null) {
            return null;
        }
        DatabaseConfig config = configs.findById(id).orElse(null);
        if (config == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", config.getId());
        out.put("name", config.getName());
        out.put("type", config.getType() == null ? null : config.getType().name());
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
            return etlTasks.isRunning(taskId);
        } catch (RuntimeException e) {
            // An in-memory guard read must not fail the whole diagnosis.
            return false;
        }
    }

    /** Null means unknown (store unreadable), not "no checkpoint": the two mean different causes. */
    private Boolean checkpointPresent(long taskId) {
        try {
            return checkpoints.load(taskId) != null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * How many of the newest runs in a row failed. With no history rows — an installation that
     * predates the history table — the task row's last outcome is the only evidence there is.
     */
    private int consecutiveFailures(EtlTask task, List<EtlRun> history) {
        if (history.isEmpty()) {
            return Boolean.FALSE.equals(task.getLastRunSuccess()) ? 1 : 0;
        }
        int count = 0;
        for (EtlRun run : history) {
            if (Boolean.FALSE.equals(run.getSuccess())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private Instant lastSuccess(EtlTask task, List<EtlRun> history) {
        for (EtlRun run : history) {
            if (Boolean.TRUE.equals(run.getSuccess()) && run.getOccurredAt() != null) {
                return run.getOccurredAt();
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
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
