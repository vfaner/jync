package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlRun;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.etl.EtlCheckpointStore;
import com.qqmu.jync.service.etl.EtlCheckpointValue;
import com.qqmu.jync.service.etl.EtlTaskService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * "The ETL task did not bring today's data": the operator needs the schedule, whether the task
 * actually fired, the latest failure sentence and enough history to tell "no new rows at the
 * source" from "the run has been failing since midnight" — in one call, without opening either
 * database or reading the process log.
 */
class DiagnoseEtlTaskToolTest {

    private static final long TASK_ID = 9L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final EtlTaskRepository tasks = mock(EtlTaskRepository.class);
    private final EtlRunRepository runs = mock(EtlRunRepository.class);
    private final DatabaseConfigService configs = mock(DatabaseConfigService.class);
    private final EtlTaskService etlTasks = mock(EtlTaskService.class);
    private final EtlCheckpointStore checkpoints = mock(EtlCheckpointStore.class);

    private DiagnoseEtlTaskTool tool() {
        return new DiagnoseEtlTaskTool(tasks, runs, configs, etlTasks, checkpoints);
    }

    private static EtlTask task() {
        EtlTask task = new EtlTask();
        task.setId(TASK_ID);
        task.setName("orders_daily");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setTargetTable("ods_orders");
        task.setWriteMode(EtlWriteMode.APPEND);
        task.setCronExpression("0 0 2 * * *");
        task.setEnabled(true);
        task.setRangeCondition("dt >= '${date}'");
        task.setResumeKeyColumn("id");
        task.setUpsertKeyColumns("id,dt");
        task.setRunIndex(12);
        task.setOwnerUsername("admin");
        return task;
    }

    private static DatabaseConfig connection(long id, String name, DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        return config;
    }

    private static EtlRun run(boolean success, int rows, Instant at) {
        EtlRun run = new EtlRun();
        run.setEtlTaskId(TASK_ID);
        run.setSuccess(success);
        run.setAffectedRows(rows);
        run.setOccurredAt(at);
        return run;
    }

    private JsonNode run(ObjectNode args) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));
    }

    @Test
    void anUnknownTaskIsReportedOnceAndNothingElseIsRead() throws Exception {
        when(tasks.findById(404L)).thenReturn(Optional.empty());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", 404L);
        JsonNode out = run(args);

        assertThat(out.path("error").asString()).isEqualTo("error.etl.not.found");
        verifyNoInteractions(runs, configs, etlTasks, checkpoints);
    }

    @Test
    void reportsScheduleConnectionsProgressAndRunHistory() throws Exception {
        EtlTask task = task();
        Instant now = Instant.now();
        task.setLastRunTime(now.minusSeconds(600));
        task.setLastRunSuccess(false);
        task.setLastRunResult("FAILED side=TARGET error=" + "x".repeat(400));
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(configs.findById(1L)).thenReturn(Optional.of(
                connection(1L, "src", DatabaseType.MYSQL)));
        when(configs.findById(2L)).thenReturn(Optional.of(
                connection(2L, "dw", DatabaseType.POSTGRESQL)));
        when(etlTasks.isRunning(TASK_ID)).thenReturn(true);
        when(checkpoints.load(TASK_ID)).thenReturn(
                new EtlCheckpointValue(java.sql.Types.BIGINT, "1042"));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of(
                run(false, 0, now.minusSeconds(600)),
                run(false, 0, now.minusSeconds(4_200)),
                run(true, 350, now.minusSeconds(7_800)),
                run(true, 410, now.minusSeconds(11_400))));

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("name").asString()).isEqualTo("orders_daily");
        assertThat(out.path("enabled").asBoolean()).isTrue();
        assertThat(out.path("cron").asString()).isEqualTo("0 0 2 * * *");
        assertThat(out.path("cronValid").asBoolean()).isTrue();
        assertThat(out.path("writeMode").asString()).isEqualTo("APPEND");
        assertThat(out.path("targetTable").asString()).isEqualTo("ods_orders");
        assertThat(out.path("source").path("name").asString()).isEqualTo("src");
        assertThat(out.path("source").path("type").asString()).isEqualTo("MYSQL");
        assertThat(out.path("target").path("name").asString()).isEqualTo("dw");
        assertThat(out.path("running").asBoolean()).isTrue();
        assertThat(out.path("checkpointPresent").asBoolean()).isTrue();
        assertThat(out.path("runIndex").asInt()).isEqualTo(12);
        assertThat(out.path("lastRunResult").asString()).hasSize(300);
        assertThat(out.path("consecutiveFailures").asInt()).isEqualTo(2);
        assertThat(out.path("minutesSinceLastSuccess").asLong()).isBetween(129L, 131L);
        assertThat(out.path("recentRuns")).hasSize(4);
        assertThat(out.path("recentRuns").get(0).path("success").asBoolean()).isFalse();
        assertThat(out.path("recentRuns").get(2).path("affectedRows").asInt()).isEqualTo(350);
        assertThat(out.path("hasRangeCondition").asBoolean()).isTrue();
        assertThat(out.path("hasResumeKey").asBoolean()).isTrue();
        assertThat(out.path("hasUpsertKeys").asBoolean()).isTrue();

        // Configuration text and connection identity stay out of the request to the provider.
        assertThat(out.has("sourceSql")).isFalse();
        assertThat(out.has("rangeCondition")).isFalse();
        assertThat(out.has("variables")).isFalse();
        assertThat(out.path("source").has("username")).isFalse();
        assertThat(out.path("source").has("host")).isFalse();
        assertThat(out.path("source").has("password")).isFalse();
    }

    @Test
    void aMissingConnectionIsShownAsNullAndDoesNotFailTheDiagnosis() throws Exception {
        EtlTask task = task();
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(configs.findById(1L)).thenReturn(Optional.of(
                connection(1L, "src", DatabaseType.MYSQL)));
        when(configs.findById(2L)).thenReturn(Optional.empty());
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("source").path("name").asString()).isEqualTo("src");
        assertThat(out.path("target").isNull()).isTrue();
        assertThat(out.path("name").asString()).isEqualTo("orders_daily");
    }

    /**
     * A checkpoint store that is momentarily unreadable must not take the diagnosis down, and
     * "unknown" must not read as "no checkpoint" — the model would chase the wrong cause.
     */
    @Test
    void anUnreadableCheckpointStoreLeavesTheFlagUnknown() throws Exception {
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());
        when(checkpoints.load(TASK_ID)).thenThrow(new IllegalStateException("db down"));

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("checkpointPresent").isNull()).isTrue();
        assertThat(out.path("name").asString()).isEqualTo("orders_daily");
    }

    @Test
    void withNoHistoryTheTaskRowSuppliesTheLastOutcome() throws Exception {
        EtlTask task = task();
        Instant now = Instant.now();
        task.setLastRunTime(now.minusSeconds(300));
        task.setLastRunSuccess(false);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("recentRuns")).isEmpty();
        assertThat(out.path("minutesSinceLastRun").asLong()).isBetween(4L, 6L);
        assertThat(out.path("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(out.path("lastSuccessAt").isNull()).isTrue();
        assertThat(out.path("minutesSinceLastSuccess").isNull()).isTrue();
    }

    @Test
    void aDisabledTaskAndAnInvalidCronAreVisible() throws Exception {
        EtlTask task = task();
        task.setEnabled(false);
        task.setCronExpression("not a cron");
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("enabled").asBoolean()).isFalse();
        assertThat(out.path("cronValid").asBoolean()).isFalse();
    }

    @Test
    void aBlankCronOnAManualTaskHasNoValidityToReport() throws Exception {
        EtlTask task = task();
        task.setCronExpression(null);
        task.setEnabled(false);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("cron").isNull()).isTrue();
        assertThat(out.path("cronValid").isNull()).isTrue();
    }

    @Test
    void aBatchMemberCarriesItsBatchIdAndNoOwnSchedule() throws Exception {
        EtlTask task = task();
        task.setBatchId(77L);
        task.setCronExpression(null);
        task.setEnabled(false);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop20ByEtlTaskIdOrderByOccurredAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("batchId").asLong()).isEqualTo(77L);
        assertThat(out.path("cron").isNull()).isTrue();
    }

    @Test
    void theDescriptionNamesTheParametersTheSchemaDeclares() {
        assertThat(tool().description()).contains("taskId");
        assertThat(tool().allowedKeys()).containsExactly("taskId");
        assertThat(tool().parameters()).containsKey("required");
    }
}
