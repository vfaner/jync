package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.SupportArgs;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call for a reported symptom: the operator says "CDC stopped last night and the reports
 * no longer match", and the assistant needs the task state, the failed rows, the live alerts,
 * the CDC position and the switches that explain a mismatch — without spending four rounds
 * assembling them one by one.
 */
class DiagnoseProjectToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectStatusTool status = mock(ProjectStatusTool.class);
    private final CdcStatusTool cdc = mock(CdcStatusTool.class);
    private final RecentFailuresTool failures = mock(RecentFailuresTool.class);
    private final AlertsTool alerts = mock(AlertsTool.class);
    private final ChangeLogRepository changes = mock(ChangeLogRepository.class);

    private DiagnoseProjectTool tool() {
        return new DiagnoseProjectTool(projects, status, cdc, failures, alerts, changes,
                new SyncProperties());
    }

    private static Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setOwnerUsername("admin");
        return p;
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    private JsonNode run(ObjectNode args) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(tool().execute(args, ctx)));
    }

    @Test
    void mergesStateCdcFailuresAlertsAndTheSettingsThatExplainAMismatch() throws Exception {
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.setSyncDeletes(false);
        config.setTruncateBeforeInitialLoad(true);
        when(projects.loadConfig(project)).thenReturn(config);

        when(status.execute(any(), eq(ctx))).thenReturn(Map.of(
                "name", "orders", "running", true, "progress", List.of()));
        when(cdc.execute(any(), eq(ctx))).thenReturn(Map.of(
                "name", "orders", "strategy", "BINLOG_CDC", "cdc", true,
                "consumerHeldHere", true, "stats", Map.of("connected", true),
                "position", Map.of("baselineDone", true)));

        List<Map<String, Object>> failed = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            failed.add(row("summary", "failure " + i));
        }
        when(failures.execute(any(), eq(ctx))).thenReturn(failed);
        when(alerts.execute(any(), eq(ctx))).thenReturn(List.of(
                Map.of("projectId", 7L, "category", "CDC_STOPPED"),
                Map.of("projectId", 8L, "category", "SYNC_FAILED")));

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        JsonNode out = run(args);

        assertThat(out.path("name").asString()).isEqualTo("orders");
        assertThat(out.path("running").asBoolean()).isTrue();
        assertThat(out.path("settings").path("captureStrategy").asString())
                .isEqualTo("BINLOG_CDC");
        assertThat(out.path("settings").path("syncDeletes").asBoolean()).isFalse();
        assertThat(out.path("settings").path("truncateBeforeInitialLoad").asBoolean()).isTrue();
        assertThat(out.path("settings").path("fullCompareMaxRows").asLong())
                .isEqualTo(20_000L);
        assertThat(out.path("settings").path("deleteCompareMaxRows").asLong()).isZero();
        assertThat(out.path("cdcState").path("consumerHeldHere").asBoolean()).isTrue();
        assertThat(out.path("cdcState").path("position").path("baselineDone").asBoolean())
                .isTrue();
        assertThat(out.path("recentFailures")).hasSize(10);
        assertThat(out.path("alerts")).hasSize(1);
        assertThat(out.path("alerts").get(0).path("projectId").asLong()).isEqualTo(7L);

        ArgumentCaptor<JsonNode> failureArgs = ArgumentCaptor.forClass(JsonNode.class);
        verify(failures).execute(failureArgs.capture(), eq(ctx));
        assertThat(failureArgs.getValue().path("projectId").asLong()).isEqualTo(7L);
        assertThat(failureArgs.getValue().path("hours").asInt()).isEqualTo(24);
    }

    @Test
    void flagsATargetWriteBottleneckAndSuggestsABatchIncrease() throws Exception {
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        when(projects.loadConfig(project)).thenReturn(config);
        when(status.execute(any(), eq(ctx))).thenReturn(Map.of("name", "orders",
                "running", true));
        when(cdc.execute(any(), eq(ctx))).thenReturn(Map.of(
                "consumerHeldHere", true,
                "stats", Map.of("connected", true, "lagSeconds", 120L,
                        "averageBatchMillis", 500L, "maxBatchMillis", 900L,
                        "batchRowsPerSecond", 800L),
                "position", Map.of("baselineDone", true, "ageSeconds", 10L)));
        when(failures.execute(any(), eq(ctx))).thenReturn(List.of());
        when(alerts.execute(any(), eq(ctx))).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        JsonNode out = run(args);

        assertThat(out.path("performance").path("diagnosis").asString())
                .isEqualTo("TARGET_WRITE_SLOW");
        assertThat(out.path("performance").path("suggestedBatchSize").asInt())
                .isEqualTo(2000);
        assertThat(out.path("performance").path("suggestedParameter").asString())
                .isEqualTo("sync.batch-size");
    }

    private static ChangeLog change(String table, ChangeType type, boolean success, Instant at) {
        ChangeLog row = ChangeLog.of(7L, ObjectType.TABLE, table, type, "detail");
        row.setSuccess(success);
        row.setOccurredAt(at);
        return row;
    }

    /**
     * "The source changed but the target did not" has two very different causes: Jync never
     * saw the change, or it saw it and the write failed. The change log's newest row and newest
     * successful row separate them without opening either database.
     */
    @Test
    void reportsWhetherJyncSawAChangeAndWhetherItAppliedOne() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.loadConfig(any())).thenReturn(new SyncConfig());
        when(status.execute(any(), eq(ctx))).thenReturn(Map.of("name", "orders", "running", true));
        when(cdc.execute(any(), eq(ctx))).thenReturn(Map.of("cdc", false));
        when(failures.execute(any(), eq(ctx))).thenReturn(List.of());
        when(alerts.execute(any(), eq(ctx))).thenReturn(List.of());

        Instant now = Instant.now();
        when(changes.findFirstByProjectIdOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.of(change("orders", ChangeType.UPDATE, false,
                        now.minusSeconds(120))));
        when(changes.findFirstByProjectIdAndSuccessTrueOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.of(change("orders", ChangeType.UPDATE, true,
                        now.minusSeconds(3600))));

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        JsonNode out = run(args);

        assertThat(out.path("liveness").path("lastChangeSuccess").asBoolean()).isFalse();
        assertThat(out.path("liveness").path("lastChangeType").asString()).isEqualTo("UPDATE");
        assertThat(out.path("liveness").path("lastChangeObject").asString()).isEqualTo("orders");
        assertThat(out.path("liveness").path("minutesSinceLastChange").asLong())
                .isBetween(1L, 3L);
        assertThat(out.path("liveness").path("lastSuccessAt").asString())
                .isEqualTo(now.minusSeconds(3600).toString());
        assertThat(out.path("liveness").path("minutesSinceLastSuccess").asLong())
                .isBetween(59L, 61L);
    }

    @Test
    void noChangeLogRowsAtAllMeansJyncHasSeenNothingYet() throws Exception {
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.loadConfig(any())).thenReturn(new SyncConfig());
        when(status.execute(any(), eq(ctx))).thenReturn(Map.of("name", "orders", "running", true));
        when(cdc.execute(any(), eq(ctx))).thenReturn(Map.of("cdc", false));
        when(failures.execute(any(), eq(ctx))).thenReturn(List.of());
        when(alerts.execute(any(), eq(ctx))).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        JsonNode out = run(args);

        assertThat(out.path("liveness").path("lastChangeAt").isNull()).isTrue();
        assertThat(out.path("liveness").path("lastSuccessAt").isNull()).isTrue();
        assertThat(out.path("liveness").path("minutesSinceLastChange").isNull()).isTrue();
    }

    @Test
    void anUnknownProjectIsReportedOnceAndTheOtherSourcesAreNotRead() throws Exception {
        when(status.execute(any(), eq(ctx)))
                .thenReturn(Map.of("error", "error.project.not.found"));

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 404L);
        JsonNode out = run(args);

        assertThat(out.path("error").asString()).isEqualTo("error.project.not.found");
        verifyNoInteractions(cdc, failures, alerts);
        verify(projects, never()).loadConfig(any());
    }

    @Test
    void anUnreadableConfigDoesNotTakeTheDiagnosisDown() throws Exception {
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenThrow(new IllegalStateException("bad json"));
        when(status.execute(any(), eq(ctx))).thenReturn(Map.of("name", "orders"));
        when(cdc.execute(any(), eq(ctx))).thenReturn(Map.of("cdc", false));
        when(failures.execute(any(), eq(ctx))).thenReturn(List.of());
        when(alerts.execute(any(), eq(ctx))).thenReturn(List.of());

        ObjectNode args = mapper.createObjectNode();
        args.put("projectId", 7L);
        JsonNode out = run(args);

        assertThat(out.path("settings").isNull()).isTrue();
        assertThat(out.path("name").asString()).isEqualTo("orders");
    }

    @Test
    void anOutOfRangeWindowIsABadArgument() {
        ObjectNode zero = mapper.createObjectNode();
        zero.put("hours", 0);
        ObjectNode tooWide = mapper.createObjectNode();
        tooWide.put("hours", 169);

        assertThatThrownBy(() -> tool().execute(zero, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
        assertThatThrownBy(() -> tool().execute(tooWide, ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(SupportArgs.BAD_ARGS);
        verifyNoInteractions(status, cdc, failures, alerts);
    }

    @Test
    void theDescriptionNamesTheParametersTheSchemaDeclares() {
        assertThat(tool().description())
                .contains("projectId")
                .contains("hours");
        assertThat(tool().allowedKeys()).containsExactlyInAnyOrder("projectId", "hours");
    }
}
