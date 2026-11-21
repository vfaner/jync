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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.ai.support.SupportKnowledgeBase;
import com.qqmu.jync.service.ai.support.ToolContext;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.metrics.DbMetricsView;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;

import tools.jackson.databind.ObjectMapper;

class SupportToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolContext ctx = new ToolContext(mock(AccessGuard.class));

    private Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setOwnerUsername("admin");
        p.setUpdatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        return p;
    }

    private DatabaseConfig db(long id, String name, ConnectionRole role, DatabaseType type) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName(name);
        c.setRole(role);
        c.setType(type);
        c.setOwnerUsername("admin");
        return c;
    }

    private SyncProgress progressRow(String table) {
        SyncProgress p = new SyncProgress();
        p.setProjectId(7L);
        p.setObjectType(ObjectType.TABLE);
        p.setObjectName(table);
        p.setCursorStrategy("TIMESTAMP");
        p.setLastSyncValue("2026-11-18 10:00:00");
        p.setLastSyncTime(Instant.parse("2026-11-19T00:00:00Z"));
        return p;
    }

    @Test
    void listProjectsTrimsToVisibleFields() throws Exception {
        ProjectRepository repo = mock(ProjectRepository.class);
        ProjectService projects = mock(ProjectService.class);
        when(repo.findAll()).thenReturn(List.of(project(7L, "orders-sync")));
        when(projects.isRunning(any())).thenReturn(true);

        var tool = new ListProjectsTool(repo, projects);
        var out = mapper.readTree(mapper.writeValueAsString(
                tool.execute(mapper.createObjectNode(), ctx)));

        assertThat(out.get(0).path("id").asLong()).isEqualTo(7L);
        assertThat(out.get(0).path("running").asBoolean()).isTrue();
        assertThat(out.get(0).has("syncConfig")).isFalse();
    }

    @Test
    void listConnectionsNeverLeaksHostOrCredentials() throws Exception {
        DatabaseConfigService configs = mock(DatabaseConfigService.class);
        when(configs.findAll()).thenReturn(List.of(
                db(1L, "prod-mysql", ConnectionRole.SOURCE, DatabaseType.MYSQL)));

        var tool = new ListConnectionsTool(configs);
        String json = mapper.writeValueAsString(
                tool.execute(mapper.createObjectNode(), ctx));

        assertThat(json).contains("prod-mysql").contains("SOURCE");
        assertThat(json).doesNotContain("host").doesNotContain("username")
                .doesNotContain("password").doesNotContain("jdbc:");
    }

    @Test
    void rowCountScansAreCappedAndRepeatsComeFromCache() throws Exception {
        TablePreviewService preview = mock(TablePreviewService.class);
        ProjectRepository repo = mock(ProjectRepository.class);
        when(preview.rowCount(any(), any())).thenReturn(new TableRowCount("orders",
                new TableRowCount.SideCount(TableRowCount.Status.OK, 120L, 12),
                new TableRowCount.SideCount(TableRowCount.Status.OK, 118L, 11), 23));
        when(repo.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(ctx.actor().canEdit("admin")).thenReturn(true);

        var tool = new ProjectRowCountTool(preview, repo);
        for (String table : List.of("t1", "t2", "t3")) {
            var args = mapper.createObjectNode();
            args.put("projectId", 7);
            args.put("table", table);
            tool.execute(args, ctx);
        }

        // The quota counts scans, not questions: a fourth distinct table is refused.
        var fourth = mapper.createObjectNode();
        fourth.put("projectId", 7);
        fourth.put("table", "t4");
        assertThat(mapper.writeValueAsString(tool.execute(fourth, ctx)))
                .contains("error.support.tooManyQuestions");

        // Another conversation asking a scanned table gets the cache, not a new scan.
        AccessGuard otherActor = mock(AccessGuard.class);
        when(otherActor.canEdit("admin")).thenReturn(true);
        ToolContext other = new ToolContext(otherActor);
        var first = mapper.createObjectNode();
        first.put("projectId", 7);
        first.put("table", "t1");
        String cached = mapper.writeValueAsString(tool.execute(first, other));
        assertThat(cached).contains("\"cached\":true");
        verify(preview, times(3)).rowCount(any(), any());
    }

    /**
     * The row-count page refuses a project the operator cannot edit; the assistant must refuse
     * the same project rather than running COUNT(*) on both sides with the owner's stored
     * connections. The refusal also comes before the cache, so one operator's scan is never
     * handed to another.
     */
    @Test
    void rowCountRefusesAnotherOperatorsProject() {
        TablePreviewService preview = mock(TablePreviewService.class);
        ProjectRepository repo = mock(ProjectRepository.class);
        Project foreign = project(7L, "orders");
        foreign.setOwnerUsername("someone-else");
        when(repo.findById(7L)).thenReturn(Optional.of(foreign));
        var tool = new ProjectRowCountTool(preview, repo);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        args.put("table", "orders");

        assertThat(tool.execute(args, ctx))
                .isEqualTo(Map.of("error", "error.ownership.reference:orders"));
        verify(preview, times(0)).rowCount(any(), any());
    }

    @Test
    void databaseMetricsRejectsTheSevenDayWindow() {
        var tool = new DatabaseMetricsTool(mock(MetricsQueryService.class),
                mock(DatabaseConfigService.class));
        var args = mapper.createObjectNode();
        args.put("databaseId", 1);
        args.put("window", "7d");
        assertThat(tool.execute(args, ctx))
                .isEqualTo(Map.of("error", "error.support.tool.args"));
    }

    @Test
    void databaseMetricsPassesTheWindowThrough() throws Exception {
        MetricsQueryService metrics = mock(MetricsQueryService.class);
        DatabaseConfigService configs = mock(DatabaseConfigService.class);
        when(configs.findById(1L)).thenReturn(Optional.of(
                db(1L, "prod", ConnectionRole.SOURCE, DatabaseType.MYSQL)));
        when(metrics.databaseCurrent(1L, MetricsRange.LAST_HOUR))
                .thenReturn(new DbMetricsView(true, null, Instant.parse("2026-11-19T00:00:00Z"),
                        99.5, List.of(), Map.of("sessions", 12.0)));

        when(ctx.actor().canEdit("admin")).thenReturn(true);
        var tool = new DatabaseMetricsTool(metrics, configs);
        var args = mapper.createObjectNode();
        args.put("databaseId", 1);
        args.put("window", "1h");
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"sessions\":12.0").contains("\"uptimePercent\":99.5");
    }

    /**
     * The performance page refuses a connection the operator cannot edit; the assistant must
     * not become a way around that gate for the same connection.
     */
    @Test
    void databaseMetricsRefusesAnotherOperatorsConnection() {
        MetricsQueryService metrics = mock(MetricsQueryService.class);
        DatabaseConfigService configs = mock(DatabaseConfigService.class);
        DatabaseConfig foreign = db(1L, "prod-mysql", ConnectionRole.SOURCE, DatabaseType.MYSQL);
        foreign.setOwnerUsername("someone-else");
        when(configs.findById(1L)).thenReturn(Optional.of(foreign));
        var tool = new DatabaseMetricsTool(metrics, configs);
        var args = mapper.createObjectNode();
        args.put("databaseId", 1);
        args.put("window", "1h");

        assertThat(tool.execute(args, ctx))
                .isEqualTo(Map.of("error", "error.ownership.reference:prod-mysql"));
        verifyNoInteractions(metrics);
    }

    @Test
    void projectStatusReportsCycleAndClampsProgressAndNeverOpensAConnection() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        SyncProgressRepository progressRows = mock(SyncProgressRepository.class);
        ChangeLogRepository changes = mock(ChangeLogRepository.class);
        MetricsQueryService metrics = mock(MetricsQueryService.class);

        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.isRunning(any())).thenReturn(true);
        when(projects.activeCycle(7L)).thenReturn(new ProjectService.CycleView(
                true, null, null, 3, false, 10));
        when(progressRows.findByProjectId(7L)).thenReturn(List.of(progressRow("orders")));
        when(changes.findFirstByProjectIdAndSuccessFalseOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.empty());
        when(metrics.projectCurrent(7L))
                .thenReturn(Map.of("rowsTotal", 100.0, "lag", 12.0, "failures", 0.0));

        var tool = new ProjectStatusTool(projects, progressRows, changes, metrics);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);
        String json = mapper.writeValueAsString(tool.execute(args, ctx));

        assertThat(json).contains("\"running\":true")
                .contains("\"minutesSinceProgress\":3")
                .contains("\"progress\"");
    }

    /**
     * docs/security-data-boundary.md promises that checkpoint values are not sent to the
     * external model — only whether one exists. A cursor is often a business value (account
     * number, timestamp) and the previous version shipped it verbatim in the tool result.
     */
    @Test
    void projectStatusDoesNotSendTheCursorValueToTheModel() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        SyncProgressRepository progressRows = mock(SyncProgressRepository.class);
        ChangeLogRepository changes = mock(ChangeLogRepository.class);
        MetricsQueryService metrics = mock(MetricsQueryService.class);

        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.isRunning(any())).thenReturn(true);
        when(projects.activeCycle(7L)).thenReturn(null);
        when(progressRows.findByProjectId(7L)).thenReturn(List.of(progressRow("orders")));
        when(changes.findFirstByProjectIdAndSuccessFalseOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.empty());
        when(metrics.projectCurrent(7L)).thenReturn(Map.of());

        var tool = new ProjectStatusTool(projects, progressRows, changes, metrics);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        String json = mapper.writeValueAsString(tool.execute(args, ctx));
        assertThat(json).doesNotContain("2026-11-18 10:00:00");
        assertThat(json).contains("\"hasSyncValue\":true");
    }

    /** A driver error in the change log must not carry a URL credential to the provider. */
    @Test
    void theLastErrorSummaryIsRedactedBeforeItLeaves() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        SyncProgressRepository progressRows = mock(SyncProgressRepository.class);
        ChangeLogRepository changes = mock(ChangeLogRepository.class);
        MetricsQueryService metrics = mock(MetricsQueryService.class);

        com.qqmu.jync.model.ChangeLog failure = new com.qqmu.jync.model.ChangeLog();
        failure.setProjectId(7L);
        failure.setErrorDetail("connect reset while talking to "
                + "jdbc:mysql://root:hunter2@db.internal:3306/x?password=hunter2 then retry");

        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.isRunning(any())).thenReturn(false);
        when(projects.activeCycle(7L)).thenReturn(null);
        when(progressRows.findByProjectId(7L)).thenReturn(List.of());
        when(changes.findFirstByProjectIdAndSuccessFalseOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.of(failure));
        when(metrics.projectCurrent(7L)).thenReturn(Map.of());

        var tool = new ProjectStatusTool(projects, progressRows, changes, metrics);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        String json = mapper.writeValueAsString(tool.execute(args, ctx));
        assertThat(json).doesNotContain("hunter2");
        assertThat(json).contains("<redacted>");
    }

    @Test
    void projectStatusSurvivesAnUnreadableCycle() throws Exception {
        ProjectService projects = mock(ProjectService.class);
        SyncProgressRepository progressRows = mock(SyncProgressRepository.class);
        ChangeLogRepository changes = mock(ChangeLogRepository.class);
        MetricsQueryService metrics = mock(MetricsQueryService.class);

        when(projects.findById(7L)).thenReturn(Optional.of(project(7L, "orders")));
        when(projects.isRunning(any())).thenReturn(false);
        when(projects.activeCycle(7L)).thenThrow(new IllegalStateException("lock table gone"));
        when(progressRows.findByProjectId(7L)).thenReturn(List.of());
        when(changes.findFirstByProjectIdAndSuccessFalseOrderByOccurredAtDesc(7L))
                .thenReturn(Optional.empty());
        when(metrics.projectCurrent(7L)).thenReturn(Map.of());

        var tool = new ProjectStatusTool(projects, progressRows, changes, metrics);
        var args = mapper.createObjectNode();
        args.put("projectId", 7);

        assertThat(mapper.writeValueAsString(tool.execute(args, ctx)))
                .contains("\"cycle\":null");
    }

    /**
     * The description is the model's only hint about argument names; the JSON schema is the
     * other. A description that names a parameter the schema does not have sends the model into
     * retry loops of error.support.tool.args — database_metrics said "connectionId" while the
     * schema says "databaseId".
     */
    @Test
    void toolDescriptionsNameTheParametersTheirSchemaDeclares() {
        assertThat(new DatabaseMetricsTool(mock(MetricsQueryService.class),
                mock(DatabaseConfigService.class)).description())
                .contains("databaseId")
                .doesNotContain("connectionId");
        assertThat(new ListConnectionsTool(mock(DatabaseConfigService.class)).description())
                .contains("databaseId")
                .doesNotContain("connectionId");
        assertThat(new DraftProjectTool(mock(com.qqmu.jync.service.DatabaseConfigService.class),
                mock(com.qqmu.jync.repository.RedisTargetRepository.class),
                mock(com.qqmu.jync.service.ai.support.SupportDraftStore.class)).description())
                .contains("sourceDbId")
                .contains("targetDbId");
    }

    @Test
    void searchDocsReturnsBoundedExcerpts() throws Exception {
        var tool = new SearchDocsTool(new SupportKnowledgeBase());
        var args = mapper.createObjectNode();
        args.put("query", "Oracle CDC 挖掘账号");
        var out = mapper.readTree(mapper.writeValueAsString(tool.execute(args, ctx)));
        assertThat(out.get(0).path("section").asString()).isEqualTo("Oracle CDC 配置");
        assertThat(out.get(0).path("excerpt").asString().length()).isLessThanOrEqualTo(600);
    }
}
