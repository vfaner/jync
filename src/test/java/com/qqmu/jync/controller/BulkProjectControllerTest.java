package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.bulk.BulkPlan;
import com.qqmu.jync.dto.bulk.BulkPlanRequest;
import com.qqmu.jync.dto.bulk.StatsSource;
import com.qqmu.jync.dto.bulk.TableEntry;
import com.qqmu.jync.dto.bulk.TaskPlan;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.bulk.BulkPlanService;

/**
 * The bulk-create wizard's HTTP surface: the form, the preview, the create redirect and the
 * CSV download.
 *
 * <p>The service is mocked — its own behaviour is pinned in {@code BulkPlanServiceTest} — so
 * what is asserted here is the wiring a user sees: the form lists role-filtered connections,
 * the preview echoes the submitted request, create redirects to the list with a success
 * banner, a rejected create keeps the user on the wizard page with the error, and the export
 * answers a real attachment with a UTF-8 BOM.
 */
@WebMvcTest(BulkProjectController.class)
@Import({GlobalModelAdvice.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class BulkProjectControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BulkPlanService bulkPlanService;

    @MockBean
    private DatabaseConfigService databaseConfigService;

    private static BulkPlan samplePlan() {
        return new BulkPlan("源库", "目标库", "shop",
                List.of(new TaskPlan("批量任务-01",
                        List.of(new TableEntry("orders", 1_000L, 8_192L, StatsSource.CATALOG)),
                        1_000L)),
                1, 1_000L, 0, true, false, false, false);
    }

    private static DatabaseConfig config(Long id, String name) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName(name);
        return config;
    }

    @Test
    void theFormOffersTheRoleFilteredConnections() throws Exception {
        DatabaseConfig source = config(1L, "源库");
        DatabaseConfig target = config(2L, "目标库");
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(source));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of(target));

        mvc.perform(get("/projects/bulk"))
                .andExpect(status().isOk())
                .andExpect(view().name("bulk-plan"))
                .andExpect(model().attribute("sourceDatabases", List.of(source)))
                .andExpect(model().attribute("targetDatabases", List.of(target)));
    }

    @Test
    void theFormTellsThePageWhichStrategiesEachSourceSupports() throws Exception {
        DatabaseConfig oracle = config(1L, "oracle测试");
        oracle.setType(DatabaseType.ORACLE);
        DatabaseConfig yashan = config(3L, "崖山");
        yashan.setType(DatabaseType.YASHANDB);
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(oracle, yashan));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of());

        mvc.perform(get("/projects/bulk"))
                .andExpect(status().isOk())
                // Each source option carries the strategies it can actually run, so the
                // page can narrow the dropdown the moment the source changes.
                .andExpect(content().string(containsString(
                        "data-strategies=\"POLLING,FULL_COMPARE,LOGMINER_CDC\"")))
                // YashanDB has no LogMiner (measured) — its option promises only the
                // type-agnostic strategies.
                .andExpect(content().string(containsString(
                        "data-strategies=\"POLLING,FULL_COMPARE\"")));
    }

    /**
     * 下拉框重建时用的标签必须就是服务端那一份:脚本里再抄一遍枚举→文案的映射,新增一种
     * 采集方式时漏改一处,前端就会把它当成"没有标签"从下拉框里悄悄扔掉(脚本的解码
     * 条件是 {@code !labels[name] → 跳过})。这条钉住服务端把整张表内联给页面。
     */
    @Test
    void thePageInlinesTheServerStrategyLabelsInsteadOfItsOwnCopy() throws Exception {
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE)).thenReturn(List.of());
        when(databaseConfigService.findByRole(ConnectionRole.TARGET)).thenReturn(List.of());

        mvc.perform(get("/projects/bulk"))
                .andExpect(status().isOk())
                // 内联后的对象字面量(引号由 Thymeleaf 的 JS 序列化器加上)。
                .andExpect(content().string(containsString("\"LOGMINER_CDC\":\"")))
                .andExpect(content().string(containsString("\"PG_LOGICAL_CDC\":\"")));
    }

    @Test
    void theStrategyListFollowsTheSelectedSourceOnReRender() throws Exception {        DatabaseConfig oracle = config(1L, "oracle测试");
        oracle.setType(DatabaseType.ORACLE);
        when(databaseConfigService.findByRole(ConnectionRole.SOURCE))
                .thenReturn(List.of(oracle));
        when(databaseConfigService.findByRole(ConnectionRole.TARGET))
                .thenReturn(List.of());
        when(bulkPlanService.plan(any())).thenReturn(samplePlan());

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"LOGMINER_CDC\"")))
                // A no-JS submit must not be shown a binlog option the save path refuses.
                .andExpect(content().string(not(containsString("value=\"BINLOG_CDC\""))));
    }

    @Test
    void thePreviewSendsTheSubmittedValuesAndRendersThePlan() throws Exception {
        BulkPlan plan = samplePlan();
        when(bulkPlanService.plan(any())).thenReturn(plan);

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务")
                        .param("exactStats", "true")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(view().name("bulk-plan"))
                .andExpect(model().attribute("plan", plan))
                // The per-row stats source must resolve to its own label: a mismatch in the
                // enum switch would silently show "Unknown" for every table.
                .andExpect(content().string(containsString("<span>Catalog</span>")))
                .andExpect(content().string(not(containsString("<span>Unknown</span>"))));

        ArgumentCaptor<BulkPlanRequest> captor = ArgumentCaptor.forClass(BulkPlanRequest.class);
        verify(bulkPlanService).plan(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new BulkPlanRequest(1L, 2L, 3, "批量任务", null, true));
    }

    @Test
    void thePreviewSaysHowManyTablesAreMissingFromTheTotals() throws Exception {
        // "about 1000 rows" over a plan with unknowns reads as a total; the line says what
        // the number leaves out so the page and the CSV are not mistaken for a full count.
        BulkPlan plan = new BulkPlan("源库", "目标库", "shop",
                List.of(new TaskPlan("批量任务-01",
                        List.of(new TableEntry("orders", 1_000L, 8_192L, StatsSource.CATALOG),
                                new TableEntry("audit", null, null, StatsSource.NONE)),
                        1_000L)),
                2, 1_000L, 1, true, false, false, false);
        when(bulkPlanService.plan(any())).thenReturn(plan);

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "1")
                        .param("namePrefix", "批量任务")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("1 table(s) have no size estimate")));
    }

    @Test
    void aPlanWithoutAnyStatisticsSaysSo() throws Exception {
        when(bulkPlanService.plan(any())).thenReturn(new BulkPlan("源库", "目标库", "shop",
                List.of(new TaskPlan("批量任务-01",
                        List.of(new TableEntry("orders", null, null, StatsSource.NONE)),
                        0L)),
                1, 0L, 1, false, false, false, false));

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "1")
                        .param("namePrefix", "批量任务")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("returned no catalog statistics")));
    }

    @Test
    void aPlanWithoutStatisticsDoesNotTellTheUserToTickWhatIsAlreadyTicked() throws Exception {
        // Exact counting was requested and still produced nothing (the source refuses
        // COUNT(*), or every table vanished between listing and counting). Telling the user
        // to tick the box they ticked reads as a bug in the page.
        when(bulkPlanService.plan(any())).thenReturn(new BulkPlan("源库", "目标库", "shop",
                List.of(new TaskPlan("批量任务-01",
                        List.of(new TableEntry("orders", null, null, StatsSource.NONE)),
                        0L)),
                1, 0L, 1, false, false, false, false));

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "1")
                        .param("namePrefix", "批量任务")
                        .param("exactStats", "true")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("returned no catalog statistics")))
                .andExpect(content().string(
                        not(containsString("Tick \"Count exact rows\" above"))))
                .andExpect(content().string(
                        containsString("Exact counting returned no row counts either")));
    }

    @Test
    void exactCountsSuppressTheNoCatalogWarning() throws Exception {
        // Exact counting already filled every size; the catalog warning would contradict
        // the numbers in the table right below it.
        when(bulkPlanService.plan(any())).thenReturn(new BulkPlan("源库", "目标库", "shop",
                List.of(new TaskPlan("批量任务-01",
                        List.of(new TableEntry("orders", 42L, null, StatsSource.EXACT)),
                        42L)),
                1, 42L, 0, false, true, false, false));

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "1")
                        .param("namePrefix", "批量任务")
                        .param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        not(containsString("returned no catalog statistics"))));
    }

    @Test
    void aRejectedPreviewStaysOnTheFormWithTheError() throws Exception {
        when(bulkPlanService.plan(any()))
                .thenThrow(new IllegalArgumentException("error.project.bulk.no.tables"));

        mvc.perform(post("/projects/bulk/preview").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().isOk())
                .andExpect(view().name("bulk-plan"))
                .andExpect(model().attribute("error", "error.project.bulk.no.tables"));
    }

    @Test
    void createRedirectsToTheListWithASuccessBanner() throws Exception {
        when(bulkPlanService.create(any())).thenReturn(3);

        mvc.perform(post("/projects/bulk/create").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects"))
                .andExpect(flash().attribute("message", "msg.project.bulk.created"));
    }

    @Test
    void aRejectedCreateRedirectsToTheWizardWithTheError() throws Exception {
        when(bulkPlanService.create(any()))
                .thenThrow(new IllegalArgumentException(
                        "error.project.bulk.name.duplicate:批量任务-01"));

        mvc.perform(post("/projects/bulk/create").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/bulk"))
                .andExpect(flash().attribute("error",
                        "error.project.bulk.name.duplicate:批量任务-01"));
    }

    @Test
    void theFormExplainsWhenNoConnectionExists() throws Exception {
        // An empty role list renders only "-- select --"; without the hint the required
        // selector looks broken rather than empty.
        mvc.perform(get("/projects/bulk").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("No connection is classified as a source yet")));
    }

    @Test
    void aRejectedExportRedirectsToTheWizardWithTheError() throws Exception {
        when(bulkPlanService.plan(any()))
                .thenThrow(new IllegalArgumentException("error.project.bulk.source.failed"));

        mvc.perform(post("/projects/bulk/export").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/bulk"))
                .andExpect(flash().attribute("error", "error.project.bulk.source.failed"));
    }

    @Test
    void theExportIsAnAttachmentWithABomAndLocalizedHeader() throws Exception {
        BulkPlan plan = samplePlan();
        when(bulkPlanService.plan(any())).thenReturn(plan);
        when(bulkPlanService.toCsv(eq(plan), any())).thenReturn("﻿header\r\nrow\r\n");

        mvc.perform(post("/projects/bulk/export").with(csrf())
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("taskCount", "3")
                        .param("namePrefix", "批量任务"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("attachment; filename=\"bulk-plan.csv\"")))
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(content().string(startsWith("﻿")));

        ArgumentCaptor<BulkPlanService.CsvLabels> labels =
                ArgumentCaptor.forClass(BulkPlanService.CsvLabels.class);
        verify(bulkPlanService).toCsv(eq(plan), labels.capture());
        assertThat(labels.getValue().header()).hasSize(6)
                .allSatisfy(label -> assertThat(label).isNotBlank());
        assertThat(labels.getValue().unknown()).isNotBlank();
    }
}
