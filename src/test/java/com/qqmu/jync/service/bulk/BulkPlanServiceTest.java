package com.qqmu.jync.service.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.bulk.BulkPlan;
import com.qqmu.jync.dto.bulk.BulkPlanRequest;
import com.qqmu.jync.dto.bulk.TaskPlan;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The planner behind the bulk task wizard: read a listing, bin it, and — on the create call —
 * persist one disabled task per bin.
 *
 * <p>The tests pin the decisions a user would notice: a listing with no catalog statistics is
 * reported as such instead of being silently counted row by row, exact counting only runs when
 * asked and stops at its budget, task names are refused before anything is written, and every
 * created task carries its own table selection with the explicit-selection flag.
 */
class BulkPlanServiceTest {

    private DatabaseConfigService configService;
    private DataSourceManager dataSourceManager;
    private MetadataReaderFactory readerFactory;
    private ProjectRepository projectRepository;
    private ProjectService projectService;
    private MetadataReader reader;
    private HikariDataSource dataSource;
    private Connection connection;

    private final DatabaseConfig source = new DatabaseConfig();
    private final DatabaseConfig target = new DatabaseConfig();

    @BeforeEach
    void setUp() throws SQLException {
        configService = mock(DatabaseConfigService.class);
        dataSourceManager = mock(DataSourceManager.class);
        readerFactory = mock(MetadataReaderFactory.class);
        projectRepository = mock(ProjectRepository.class);
        projectService = mock(ProjectService.class);
        reader = mock(MetadataReader.class);
        dataSource = mock(HikariDataSource.class);
        connection = mock(Connection.class);

        source.setId(1L);
        source.setName("源库");
        source.setType(DatabaseType.MYSQL);
        source.setSchemaName("shop");
        source.setRole(ConnectionRole.SOURCE);
        target.setId(2L);
        target.setName("目标库");
        target.setType(DatabaseType.MYSQL);
        target.setRole(ConnectionRole.TARGET);

        when(configService.findById(1L)).thenReturn(Optional.of(source));
        when(configService.findById(2L)).thenReturn(Optional.of(target));
        when(readerFactory.forType(eq(DatabaseType.MYSQL), any())).thenReturn(reader);
        when(dataSourceManager.createTransientDataSource(source)).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(reader.listTableNames(connection, "shop"))
                .thenReturn(List.of("big", "mid", "small"));
        when(projectRepository.findByName(anyString())).thenReturn(Optional.empty());
    }

    private BulkPlanService service() {
        return new BulkPlanService(configService, dataSourceManager, readerFactory,
                projectRepository, projectService, 120_000L);
    }

    private static Map<String, MetadataReader.TableStat> stats(String name, Long rows, Long bytes) {
        return Map.of(name, new MetadataReader.TableStat(rows, bytes));
    }

    private static Map<String, MetadataReader.TableStat> merge(
            Map<String, MetadataReader.TableStat>... maps) {
        return java.util.Arrays.stream(maps)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    void binsTheListingIntoNamedTasks() throws SQLException {
        when(reader.estimateTableStats(connection, "shop")).thenReturn(merge(
                stats("big", 1_000L, 8_192L),
                stats("mid", 500L, 4_096L),
                stats("small", 100L, 1_024L)));

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 2, "同步任务", null, false));

        assertThat(plan.taskCount()).isEqualTo(2);
        assertThat(plan.tableCount()).isEqualTo(3);
        assertThat(plan.tasks()).extracting(TaskPlan::name)
                .containsExactly("同步任务-01", "同步任务-02");
        assertThat(plan.tasks().get(0).tables()).extracting(entry -> entry.name())
                .containsExactly("big");
        assertThat(plan.tasks().get(1).tables()).extracting(entry -> entry.name())
                .containsExactly("mid", "small");
        assertThat(plan.estimatedRows()).isEqualTo(1_600L);
        assertThat(plan.unknownTables()).isZero();
        assertThat(plan.catalogStatsUsed()).isTrue();
        assertThat(plan.sourceName()).isEqualTo("源库");
        assertThat(plan.targetName()).isEqualTo("目标库");
        assertThat(plan.schema()).isEqualTo("shop");
    }

    @Test
    void aDictionaryThatReturnsNothingIsReportedRatherThanCounted() throws SQLException {
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 2, "t", null, false));

        assertThat(plan.catalogStatsUsed()).isFalse();
        assertThat(plan.unknownTables()).isEqualTo(3);
        verify(connection, never()).prepareStatement(contains("COUNT(*)"));
    }

    @Test
    void exactCountingFillsUnknownsWhenTheUserAsks() throws SQLException {
        stubCountStar(42L);
        when(reader.estimateTableStats(connection, "shop"))
                .thenReturn(stats("big", 1_000L, null));

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 2, "t", null, true));

        assertThat(plan.exactStatsUsed()).isTrue();
        assertThat(plan.exactStatsTruncated()).isFalse();
        assertThat(plan.unknownTables()).isZero();
        assertThat(plan.estimatedRows()).isEqualTo(1_084L);
    }

    @Test
    void anOracleFamilySchemaIsFoldedToUpperCaseBeforeCounting() throws SQLException {
        // Oracle-family dictionaries fold unquoted identifiers to upper case, and the sync
        // path folds a configured schema the same way (SyncContextFactory). The COUNT below
        // quotes the name verbatim: passing "scott" through would address a schema that does
        // not exist and every exact count would silently fail back to "unknown".
        source.setType(DatabaseType.ORACLE);
        source.setSchemaName("scott");
        when(readerFactory.forType(eq(DatabaseType.ORACLE), any())).thenReturn(reader);
        // Any schema argument: the listing works either way (the reader folds internally);
        // the assertion is about the name the COUNT statement is built from.
        when(reader.listTableNames(eq(connection), anyString())).thenReturn(List.of("EMP"));
        when(reader.estimateTableStats(eq(connection), anyString())).thenReturn(Map.of());
        stubCountStar(7L, "\"");

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 1, "t", null, true));

        verify(connection).prepareStatement("SELECT COUNT(*) FROM \"SCOTT\".\"EMP\"");
        assertThat(plan.exactStatsUsed()).isTrue();
        assertThat(plan.estimatedRows()).isEqualTo(7L);
    }

    @Test
    void aZeroBudgetSkipsExactCountingAndSaysSo() throws SQLException {
        stubCountStar(42L);
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = new BulkPlanService(configService, dataSourceManager, readerFactory,
                projectRepository, projectService, 0L)
                .plan(new BulkPlanRequest(1L, 2L, 2, "t", null, true));

        assertThat(plan.exactStatsUsed()).isFalse();
        assertThat(plan.exactStatsTruncated()).isTrue();
        assertThat(plan.unknownTables()).isEqualTo(3);
        verify(connection, never()).prepareStatement(contains("COUNT(*)"));
    }

    @Test
    void aFailedCountLeavesThatTableUnknownAndKeepsGoing() throws SQLException {
        PreparedStatement failing = mock(PreparedStatement.class);
        when(failing.executeQuery()).thenThrow(new SQLException("timeout"));
        when(connection.prepareStatement(contains("COUNT(*)"))).thenReturn(failing);
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 2, "t", null, true));

        assertThat(plan.unknownTables()).isEqualTo(3);
        assertThat(plan.exactStatsUsed()).isFalse();
    }

    /**
     * 下拉框只列对应角色的连接,但直连 POST 可以绕开页面。角色不符时拒绝,而不是把目标库
     * 当源库读一遍——那既浪费一次全库清单,又让用户以为"什么都没发生"。
     */
    @Test
    void aTargetRoleConnectionCannotBeUsedAsTheSource() {
        source.setRole(ConnectionRole.TARGET);

        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 2, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.source.role");
    }

    @Test
    void aSourceRoleConnectionCannotBeUsedAsTheTarget() {
        target.setRole(ConnectionRole.SOURCE);

        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 2, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.target.role");
    }

    @Test
    void aTaskCountBelowOneIsRefusedInsteadOfSilentlyBecomingOne() {
        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 0, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.taskcount.invalid");
    }

    /**
     * 精确统计的单句超时固定 15 秒,整页预算 120 秒;最后一句若仍按 15 秒计,整页可跑到
     * 135 秒。超时必须被剩余预算夹住。
     */
    @Test
    void theStatementTimeoutIsClampedToWhatThePageBudgetHasLeft() {
        assertThat(BulkPlanService.statementTimeoutSeconds(120_000L)).isEqualTo(15);
        assertThat(BulkPlanService.statementTimeoutSeconds(15_001L)).isEqualTo(15);
        assertThat(BulkPlanService.statementTimeoutSeconds(2_000L)).isEqualTo(2);
        assertThat(BulkPlanService.statementTimeoutSeconds(1L)).isEqualTo(1);
        assertThat(BulkPlanService.statementTimeoutSeconds(0L)).isZero();
        assertThat(BulkPlanService.statementTimeoutSeconds(-5L)).isZero();
    }

    /**
     * DB2 的 SYSCAT 视图把未加引号的 schema 折叠为大写(读清单和目录统计都折叠),精确
     * COUNT 却按原样引号拼接;配置里写小写 db2inst1 时每一句都查不存在的 schema、静默
     * 退回"未知"。与 Oracle 一样,拼 COUNT 之前先折叠。
     */
    @Test
    void aDb2SchemaIsFoldedToUpperCaseBeforeCounting() throws SQLException {
        source.setType(DatabaseType.DB2);
        source.setSchemaName("db2inst1");
        when(readerFactory.forType(eq(DatabaseType.DB2), any())).thenReturn(reader);
        when(reader.listTableNames(eq(connection), anyString())).thenReturn(List.of("ORDERS"));
        when(reader.estimateTableStats(eq(connection), anyString())).thenReturn(Map.of());
        stubCountStar(3L, "\"");

        service().plan(new BulkPlanRequest(1L, 2L, 1, "t", null, true));

        verify(connection).prepareStatement("SELECT COUNT(*) FROM \"DB2INST1\".\"ORDERS\"");
    }

    @Test
    void anEmptyListingIsRefused() throws SQLException {
        when(reader.listTableNames(connection, "shop")).thenReturn(List.of());

        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 2, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.no.tables");
    }

    @Test
    void syncingADatabaseOntoItselfIsRefused() {
        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 1L, 2, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.same.database");
    }

    @Test
    void moreTasksThanTablesIsClampedAndFlagged() throws SQLException {
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 10, "t", null, false));

        assertThat(plan.tasks()).hasSize(3);
        assertThat(plan.taskCountClamped()).isTrue();
    }

    @Test
    void theCsvHasABomQuotedFieldsAndOneRowPerTable() throws SQLException {
        when(reader.listTableNames(connection, "shop"))
                .thenReturn(List.of("a,b", "plain"));
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 1, "t", null, false));
        String csv = service().toCsv(plan, new BulkPlanService.CsvLabels(
                List.of("任务序号", "任务名称", "表名", "估算行数", "估算大小(字节)", "统计来源"),
                "目录统计", "精确统计", "未知"));

        assertThat(csv).startsWith("﻿");
        assertThat(csv).contains("任务序号");
        assertThat(csv).contains("1,t-01,\"a,b\",,,未知");
        assertThat(csv).contains("plain");
        assertThat(csv.lines()).hasSize(3);
    }

    @Test
    void theCsvNeutralizesFieldsThatSpreadsheetsWouldEvaluate() throws SQLException {
        // Table names and the prefix are user-controlled. Excel, Sheets and WPS evaluate a
        // cell that starts with = + - @ even when the field was quoted, so the export
        // prefixes those cells with an apostrophe.
        when(reader.listTableNames(connection, "shop"))
                .thenReturn(List.of("=cmd", "+1", "-x", "@y", "plain"));
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());

        BulkPlan plan = service().plan(new BulkPlanRequest(1L, 2L, 1, "t", null, false));
        String csv = service().toCsv(plan, new BulkPlanService.CsvLabels(
                List.of("a", "b", "c", "d", "e", "f"), "c", "e", "u"));

        assertThat(csv).contains("'=cmd").contains("'+1").contains("'-x").contains("'@y");
        assertThat(csv).contains(",plain,");
        assertThat(csv).doesNotContain(",=cmd,").doesNotContain(",+1,")
                .doesNotContain(",-x,").doesNotContain(",@y,");
    }

    @Test
    void createWritesADisabledTaskPerBinWithItsOwnSelection() throws SQLException {
        when(reader.listTableNames(connection, "shop")).thenReturn(List.of("a", "b"));
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());
        when(projectService.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L + project.getName().hashCode() % 10);
            return project;
        });

        int created = service().create(new BulkPlanRequest(1L, 2L, 2, "t", "BINLOG_CDC", false));

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Project> projects = ArgumentCaptor.forClass(Project.class);
        verify(projectService, org.mockito.Mockito.times(2)).save(projects.capture());
        assertThat(projects.getAllValues()).allSatisfy(project -> {
            assertThat(project.getSourceDbId()).isEqualTo(1L);
            assertThat(project.getTargetDbId()).isEqualTo(2L);
            assertThat(project.getEnabled()).isFalse();
        });

        ArgumentCaptor<SyncConfig> configs = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService, org.mockito.Mockito.times(2))
                .saveConfig(any(), configs.capture(), eq("BINLOG_CDC"));
        assertThat(configs.getAllValues()).allSatisfy(config -> {
            assertThat(config.isObjectSelectionExplicit()).isTrue();
            assertThat(config.getViews()).isEmpty();
            assertThat(config.getProcedures()).isEmpty();
        });
        assertThat(configs.getAllValues().get(0).getTables()).isEqualTo(new LinkedHashSet<>(Set.of("a")));
        assertThat(configs.getAllValues().get(1).getTables()).isEqualTo(new LinkedHashSet<>(Set.of("b")));
    }

    @Test
    void anExistingTaskNameStopsTheWholeCreation() throws SQLException {
        when(reader.listTableNames(connection, "shop")).thenReturn(List.of("a"));
        when(reader.estimateTableStats(connection, "shop")).thenReturn(Map.of());
        when(projectRepository.findByName("t-01"))
                .thenReturn(Optional.of(new Project()));

        assertThatThrownBy(() -> service().create(
                new BulkPlanRequest(1L, 2L, 1, "t", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.name.duplicate:t-01");
        verify(projectService, never()).save(any());
    }

    @Test
    void aBlankPrefixIsRefusedBeforeTheSourceIsTouched() {
        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 2, "  ", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.prefix.required");
    }

    @Test
    void anOverlongPrefixIsRefused() {
        assertThatThrownBy(() -> service().plan(
                new BulkPlanRequest(1L, 2L, 2, "x".repeat(101), null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.bulk.prefix.too.long");
    }

    private void stubCountStar(long count) throws SQLException {
        stubCountStar(count, "`");
    }

    private void stubCountStar(long count, String quote) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("COUNT(*)"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(count);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getIdentifierQuoteString()).thenReturn(quote);
    }
}
