package com.qqmu.jync.service.ai.support.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertState;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;

import tools.jackson.databind.ObjectMapper;

/**
 * The operations-assistant tools: CDC runtime state, the CDC capability probe, the alert board
 * and ETL task state. Every one of them is read-only, and none may become a way around the
 * ownership rule the corresponding page enforces.
 */
class OpsSupportToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    private Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setOwnerUsername("admin");
        return p;
    }

    private SyncConfig strategy(CaptureStrategy value) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(value);
        return config;
    }

    private String run(com.qqmu.jync.service.ai.support.SupportTool tool,
                       tools.jackson.databind.node.ObjectNode args) throws Exception {
        return mapper.writeValueAsString(tool.execute(args, ctx));
    }

    // —— cdc_status ——

    @Test
    void cdcStatusReportsRuntimeAndPositionWhenThisNodeHoldsTheConsumer() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        CdcConsumerManager consumers = mock(CdcConsumerManager.class);
        CdcPositionStore positions = mock(CdcPositionStore.class);
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenReturn(strategy(CaptureStrategy.BINLOG_CDC));

        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.setConnected(true);
        stats.setLastError("PURGED: binlog mysql-bin.000123 was purged");
        when(consumers.statsFor(7L)).thenReturn(stats);

        CdcPosition position = new CdcPosition();
        position.setBinlogFile("mysql-bin.000124");
        position.setBinlogPos(4096L);
        position.setUpdatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(positions.find(7L)).thenReturn(Optional.of(position));

        var tool = new CdcStatusTool(projects, consumers, positions);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        String json = run(tool, args);

        assertThat(json).contains("\"strategy\":\"BINLOG_CDC\"")
                .contains("\"cdc\":true")
                .contains("\"consumerHeldHere\":true")
                .contains("\"connected\":true")
                .contains("PURGED: binlog mysql-bin.000123 was purged")
                .contains("\"binlogFile\":\"mysql-bin.000124\"")
                .contains("\"binlogPos\":4096");
    }

    @Test
    void cdcStatusWithoutAConsumerHereStillReportsThePersistedPosition() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        CdcConsumerManager consumers = mock(CdcConsumerManager.class);
        CdcPositionStore positions = mock(CdcPositionStore.class);
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenReturn(strategy(CaptureStrategy.LOGMINER_CDC));
        when(consumers.statsFor(7L)).thenReturn(null);
        CdcPosition position = new CdcPosition();
        position.setScn(8_813_001L);
        when(positions.find(7L)).thenReturn(Optional.of(position));

        var tool = new CdcStatusTool(projects, consumers, positions);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        String json = run(tool, args);

        assertThat(json).contains("\"consumerHeldHere\":false")
                .contains("\"scn\":8813001");
    }

    @Test
    void cdcStatusForAPollingProjectSaysSoAndNeverAsksForCdcState() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        CdcConsumerManager consumers = mock(CdcConsumerManager.class);
        CdcPositionStore positions = mock(CdcPositionStore.class);
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(projects.loadConfig(project)).thenReturn(strategy(CaptureStrategy.POLLING));

        var tool = new CdcStatusTool(projects, consumers, positions);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        String json = run(tool, args);

        assertThat(json).contains("\"strategy\":\"POLLING\"").contains("\"cdc\":false");
        verifyNoInteractions(consumers, positions);
    }

    // —— cdc_probe ——

    @Test
    void cdcProbeReturnsFailedChecksWithTheirDetail() throws Exception {
        CdcOperationsService operations = mock(CdcOperationsService.class);
        ProjectService projects = mock(ProjectService.class);
        Project project = project(7L, "orders");
        when(projects.findById(7L)).thenReturn(Optional.of(project));
        when(ctx.actor().canWrite()).thenReturn(true);
        when(ctx.actor().canEdit("admin")).thenReturn(true);
        when(operations.probeProject(7L)).thenReturn(CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of("ARCHIVELOG", false, "LOG_MODE=NOARCHIVELOG"),
                CdcProbeResult.Check.of("SUPPLEMENTAL_DB", true, "YES"))));

        var tool = new CdcProbeTool(operations, projects);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        String json = run(tool, args);

        assertThat(json).contains("\"passed\":false")
                .contains("\"name\":\"ARCHIVELOG\"")
                .contains("LOG_MODE=NOARCHIVELOG");
    }

    @Test
    void cdcProbePassesAnUnsupportedSourceThroughAsItsKey() throws Exception {
        CdcOperationsService operations = mock(CdcOperationsService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(ctx.actor().canWrite()).thenReturn(true);
        when(ctx.actor().canEdit("admin")).thenReturn(true);
        when(operations.probeProject(7L))
                .thenThrow(new IllegalArgumentException("cdc.probe.unsupported"));

        var tool = new CdcProbeTool(operations, projects);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        assertThat(tool.execute(args, ctx))
                .isEqualTo(java.util.Map.of("error", "cdc.probe.unsupported"));
    }

    /**
     * Probing dials the production source, so it follows the HTTP sibling's rule: only
     * projects the caller may edit. A cross-owner probe would leak another user's source
     * capabilities (privilege names, server settings) and pay for their source load.
     */
    @Test
    void cdcProbeRefusesAProjectTheOperatorCannotEdit() throws Exception {
        CdcOperationsService operations = mock(CdcOperationsService.class);
        ProjectService projects = mock(ProjectService.class);
        Project foreign = project(7L, "orders");
        foreign.setOwnerUsername("someone-else");
        when(projects.findById(7L)).thenReturn(Optional.of(foreign));
        when(ctx.actor().canWrite()).thenReturn(true);
        when(ctx.actor().canEdit("someone-else")).thenReturn(false);

        var tool = new CdcProbeTool(operations, projects);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        assertThat(tool.execute(args, ctx))
                .isEqualTo(java.util.Map.of("error",
                        "error.ownership.reference:orders"));
        verifyNoInteractions(operations);
    }

    @Test
    void cdcProbeRefusesAViewerAndRunsNothing() {
        CdcOperationsService operations = mock(CdcOperationsService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(ctx.actor().canWrite()).thenReturn(false);

        var tool = new CdcProbeTool(operations, projects);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        assertThat(tool.execute(args, ctx))
                .isEqualTo(java.util.Map.of("error", "error.accessDenied"));
        verifyNoInteractions(operations);
    }

    @Test
    void cdcProbeIsCappedPerConversation() throws Exception {
        CdcOperationsService operations = mock(CdcOperationsService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(ctx.actor().canWrite()).thenReturn(true);
        when(ctx.actor().canEdit("admin")).thenReturn(true);
        when(operations.probeProject(7L)).thenReturn(CdcProbeResult.of(List.of()));

        var tool = new CdcProbeTool(operations, projects);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        tool.execute(args, ctx);
        tool.execute(args, ctx);
        String third = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(third).contains("error.support.tooManyQuestions");
        verify(operations, times(2)).probeProject(any());
    }

    // —— alerts ——

    @Test
    void alertsListsWhatIsActiveRightNow() throws Exception {
        AlertStateRepository repo = mock(AlertStateRepository.class);
        AlertState cdc = new AlertState();
        cdc.setCategory(AlertCategory.CDC_STOPPED);
        cdc.setProjectId(7L);
        cdc.setSubject("orders");
        cdc.setObjectKey("");
        cdc.setActive(true);
        cdc.setFirstSeenAt(Instant.parse("2026-11-19T00:00:00Z"));
        AlertState ddl = new AlertState();
        ddl.setCategory(AlertCategory.DDL_SKIPPED);
        ddl.setProjectId(7L);
        ddl.setSubject("orders");
        ddl.setObjectKey("order_items");
        ddl.setActive(true);
        ddl.setFirstSeenAt(Instant.parse("2026-11-19T01:00:00Z"));
        when(repo.findByActiveTrueOrderByFirstSeenAtDesc()).thenReturn(List.of(ddl, cdc));

        var tool = new AlertsTool(repo);
        String json = run(tool, mapper.createObjectNode());

        assertThat(json).contains("DDL_SKIPPED").contains("CDC_STOPPED")
                .contains("\"object\":\"order_items\"")
                .contains("\"since\":\"2026-11-19T01:00:00Z\"");
    }

    // —— etl_status ——

    private EtlTask etlTask(long id, String name, Boolean success, String result) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName(name);
        task.setEnabled(true);
        task.setCronExpression("0 0 2 * * ?");
        task.setLastRunTime(Instant.parse("2026-11-19T02:00:00Z"));
        task.setLastRunSuccess(success);
        task.setLastRunResult(result);
        return task;
    }

    @Test
    void etlStatusListsTasksAndFiltersByName() throws Exception {
        EtlTaskRepository repo = mock(EtlTaskRepository.class);
        when(repo.findAllByOrderByIdDesc()).thenReturn(List.of(
                etlTask(2L, "daily-orders", true, "rows=120"),
                etlTask(1L, "hourly-users", false, "error.etl.source.missing")));

        var tool = new EtlStatusTool(repo);
        var args = mapper.createObjectNode();
        args.put("query", "order");
        String json = run(tool, args);

        assertThat(json).contains("daily-orders").doesNotContain("hourly-users");
    }

    @Test
    void etlStatusReportsOneTasksLastFailureWithTheResultClamped() throws Exception {
        EtlTaskRepository repo = mock(EtlTaskRepository.class);
        String longResult = "x".repeat(1_000);
        when(repo.findById(9L)).thenReturn(Optional.of(
                etlTask(9L, "daily-orders", false, longResult)));

        var tool = new EtlStatusTool(repo);
        var args = mapper.createObjectNode();
        args.put("taskId", 9);

        var out = mapper.readTree(run(tool, args));
        assertThat(out.path("lastRunSuccess").asBoolean(true)).isFalse();
        assertThat(out.path("lastRunResult").asString().length()).isLessThanOrEqualTo(300);
    }

    @Test
    void etlStatusAnswersNotFoundForAnUnknownTask() {
        EtlTaskRepository repo = mock(EtlTaskRepository.class);
        when(repo.findById(9L)).thenReturn(Optional.empty());

        var tool = new EtlStatusTool(repo);
        var args = mapper.createObjectNode();
        args.put("taskId", 9);

        assertThat(tool.execute(args, ctx))
                .isEqualTo(java.util.Map.of("error", "error.etl.not.found"));
    }
}
