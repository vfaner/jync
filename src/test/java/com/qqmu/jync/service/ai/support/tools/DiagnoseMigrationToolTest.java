package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.migration.MigrationTaskService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * "The dedicated migration has not moved for hours": schedule and enabled state, the endpoints
 * by name, and the run history — including the in-flight run and its per-index progress — so the
 * answer can separate a stalled run from a schedule that never fired.
 */
class DiagnoseMigrationToolTest {

    private static final long TASK_ID = 5L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final MigrationTaskRepository tasks = mock(MigrationTaskRepository.class);
    private final MigrationRunRepository runs = mock(MigrationRunRepository.class);
    private final MigrationConnectionRepository connections =
            mock(MigrationConnectionRepository.class);
    private final MigrationTaskService migrationTasks = mock(MigrationTaskService.class);

    private DiagnoseMigrationTool tool() {
        return new DiagnoseMigrationTool(tasks, runs, connections, migrationTasks);
    }

    private static MigrationTask task() {
        MigrationTask task = new MigrationTask();
        task.setId(TASK_ID);
        task.setName("es-orders");
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        task.setCronExpression("0 0 3 * * *");
        task.setEnabled(true);
        task.setOwnerUsername("admin");
        return task;
    }

    private static MigrationConnection connection(long id, String name,
                                                  MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(kind);
        return connection;
    }

    private static MigrationRun run(MigrationRunStatus status, Instant startedAt) {
        MigrationRun run = new MigrationRun();
        run.setMigrationTaskId(TASK_ID);
        run.setStatus(status);
        run.setTriggerType(MigrationRunTrigger.SCHEDULE);
        run.setStartedAt(startedAt);
        run.setIndicesTotal(8);
        run.setIndicesSucceeded(3);
        run.setIndicesFailed(1);
        run.setDocumentsRead(12_000);
        run.setDocumentsWritten(11_800);
        run.setCurrentIndex("orders_2026");
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

        assertThat(out.path("error").asString()).isEqualTo("error.migration.not.found");
        verifyNoInteractions(runs, connections, migrationTasks);
    }

    @Test
    void reportsScheduleEndpointsAndRunHistory() throws Exception {
        MigrationTask task = task();
        Instant now = Instant.now();
        task.setLastRunTime(now.minusSeconds(600));
        task.setLastRunSuccess(false);
        task.setLastRunResult("FAILED indices=1/8 error=" + "x".repeat(400));
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(connections.findById(1L)).thenReturn(Optional.of(
                connection(1L, "prod-es", MigrationConnectionKind.ELASTICSEARCH)));
        when(connections.findById(2L)).thenReturn(Optional.of(
                connection(2L, "easysearch", MigrationConnectionKind.EASYSEARCH)));
        when(migrationTasks.isRunning(TASK_ID)).thenReturn(true);

        MigrationRun newest = run(MigrationRunStatus.RUNNING, now.minusSeconds(300));
        newest.setFinishedAt(null);
        newest.setMessage("y".repeat(400));
        when(runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(TASK_ID)).thenReturn(List.of(
                newest,
                run(MigrationRunStatus.FAILED, now.minusSeconds(3_600)),
                run(MigrationRunStatus.CANCELLED, now.minusSeconds(7_200)),
                run(MigrationRunStatus.SUCCESS, now.minusSeconds(10_800))));

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("name").asString()).isEqualTo("es-orders");
        assertThat(out.path("type").asString()).isEqualTo("ES_TO_EASYSEARCH");
        assertThat(out.path("enabled").asBoolean()).isTrue();
        assertThat(out.path("cron").asString()).isEqualTo("0 0 3 * * *");
        assertThat(out.path("cronValid").asBoolean()).isTrue();
        assertThat(out.path("source").path("name").asString()).isEqualTo("prod-es");
        assertThat(out.path("source").path("kind").asString()).isEqualTo("ELASTICSEARCH");
        assertThat(out.path("target").path("name").asString()).isEqualTo("easysearch");
        assertThat(out.path("running").asBoolean()).isTrue();
        assertThat(out.path("lastRunResult").asString()).hasSize(300);
        assertThat(out.path("recentRuns")).hasSize(4);
        assertThat(out.path("recentRuns").get(0).path("status").asString()).isEqualTo("RUNNING");
        assertThat(out.path("recentRuns").get(0).path("currentIndex").asString())
                .isEqualTo("orders_2026");
        assertThat(out.path("recentRuns").get(0).path("documentsWritten").asLong())
                .isEqualTo(11_800L);
        assertThat(out.path("recentRuns").get(0).path("message").asString()).hasSize(300);
        assertThat(out.path("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(out.path("minutesSinceLastSuccess").asLong()).isBetween(179L, 181L);

        // Endpoint addresses and the opaque options JSON never leave the installation.
        assertThat(out.path("source").has("baseUrl")).isFalse();
        assertThat(out.path("source").has("username")).isFalse();
        assertThat(out.has("optionsJson")).isFalse();
    }

    @Test
    void aMissingEndpointIsShownAsNullAndDoesNotFailTheDiagnosis() throws Exception {
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(connections.findById(1L)).thenReturn(Optional.empty());
        when(runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("source").isNull()).isTrue();
        assertThat(out.path("target").isNull()).isTrue();
        assertThat(out.path("name").asString()).isEqualTo("es-orders");
    }

    @Test
    void aCancelledRunIsNotCountedAsAFailure() throws Exception {
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task()));
        when(runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(TASK_ID)).thenReturn(List.of(
                run(MigrationRunStatus.CANCELLED, Instant.now().minusSeconds(60)),
                run(MigrationRunStatus.FAILED, Instant.now().minusSeconds(3_600)),
                run(MigrationRunStatus.SUCCESS, Instant.now().minusSeconds(7_200))));

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("consecutiveFailures").asInt()).isZero();
    }

    @Test
    void withNoHistoryTheTaskRowSuppliesTheLastOutcome() throws Exception {
        MigrationTask task = task();
        Instant now = Instant.now();
        task.setLastRunTime(now.minusSeconds(300));
        task.setLastRunSuccess(true);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("recentRuns")).isEmpty();
        assertThat(out.path("minutesSinceLastRun").asLong()).isBetween(4L, 6L);
        assertThat(out.path("lastSuccessAt").asString()).isEqualTo(now.minusSeconds(300).toString());
        assertThat(out.path("consecutiveFailures").asInt()).isZero();
    }

    @Test
    void aBlankCronOnAManualTaskHasNoValidityToReport() throws Exception {
        MigrationTask task = task();
        task.setCronExpression(null);
        task.setEnabled(false);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(runs.findTop50ByMigrationTaskIdOrderByStartedAtDesc(TASK_ID)).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("taskId", TASK_ID);
        JsonNode out = run(args);

        assertThat(out.path("cron").isNull()).isTrue();
        assertThat(out.path("cronValid").isNull()).isTrue();
    }

    @Test
    void theDescriptionNamesTheParametersTheSchemaDeclares() {
        assertThat(tool().description()).contains("taskId");
        assertThat(tool().allowedKeys()).containsExactly("taskId");
    }
}
