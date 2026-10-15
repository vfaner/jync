package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.cdc.CdcProbeResult;

/**
 * C1 策略卡片在控制器层的四件事:合法策略透传、非法策略拦下且不透传、非 MySQL 源库
 * 连卡片都不渲染、新建 MySQL 项目探测通过时 CDC 默认选中(探测抛异常绝不阻断渲染)。
 *
 * <p>搭法照抄 ProjectLockViewTest(@WebMvcTest + @Import(GlobalModelAdvice) +
 * github-url 属性);POST 用例带 csrf(),类级 @WithMockUser(roles="ADMIN") 满足
 * SecurityConfig 对 POST /** 的角色要求。断言全部落在结构性 HTML 片段上
 * (value=/picked/checked/data-original-strategy),不依赖渲染语言。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectControllerStrategyTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    /** Stubs the detail route for a project whose source has the given type (null = no source). */
    private Project stubDetail(DatabaseType sourceType) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        // saveConfig 测试需要 enabled=false:运行中的项目后端会拒绝改配置(兜底保护)。
        project.setEnabled(false);
        project.setSourceDbId(sourceType == null ? null : 5L);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        if (sourceType != null) {
            DatabaseConfig source = new DatabaseConfig();
            source.setId(5L);
            source.setType(sourceType);
            when(databaseConfigService.findById(5L)).thenReturn(Optional.of(source));
        }
        return project;
    }

    /** Turns the stubbed detail project into a BINLOG_CDC project (enabled per parameter). */
    private Project stubCdcDetail(boolean enabled) {
        Project project = stubDetail(DatabaseType.MYSQL);
        project.setEnabled(enabled);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        when(projectService.loadConfig(project)).thenReturn(config);
        return project;
    }

    /** The full @RequestParam list of ProjectController.saveConfig, strategy filled per case. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder configPost(
            String captureStrategy) {
        return post("/projects/1/config").with(csrf())
                .param("tables", "orders", "orders_history")
                .param("views", "v_orders")
                .param("procedures", "p_nightly")
                .param("syncStructure", "true")
                .param("syncData", "true")
                .param("syncIndexes", "true")
                .param("syncViews", "true")
                .param("syncProcedures", "true")
                .param("allowDrop", "false")
                .param("syncDeletes", "true")
                .param("disableTargetConstraints", "true")
                .param("truncateBeforeInitialLoad", "false")
                .param("pollIntervalMs", "5000")
                .param("cronExpression", "0 0/30 * * * ?")
                .param("batchSize", "500")
                .param("captureStrategy", captureStrategy);
    }

    /** The strategy-card label block whose radio carries the given value. */
    private static String cardOf(String page, String value) {
        int at = page.indexOf("value=\"" + value + "\"");
        assertThat(at).as("card for " + value).isPositive();
        int labelStart = page.lastIndexOf("<label", at);
        return page.substring(labelStart, at + value.length() + 40);
    }

    @Test
    void saveConfigAcceptsValidStrategy() throws Exception {
        stubDetail(DatabaseType.MYSQL);

        MvcResult result = mvc.perform(configPost("FULL_COMPARE"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // 无 error flash;切换成功给的是 strategy-switched 提示(before=POLLING → FULL_COMPARE)。
        assertThat(result.getFlashMap().get("error")).isNull();
        assertThat(result.getFlashMap().get("message")).isEqualTo("msg.project.strategy.switched");
        verify(projectService).saveConfig(eq(1L), any(), eq("FULL_COMPARE"));
    }

    @Test
    void saveConfigRejectsUnknownStrategy() throws Exception {
        stubDetail(DatabaseType.MYSQL);

        MvcResult result = mvc.perform(configPost("TIME_TRAVEL"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getFlashMap().get("error")).isEqualTo("error.project.strategy.invalid");
        verify(projectService).saveConfig(eq(1L), any(), isNull());   // 非法值不透传
    }

    @Test
    void saveConfigPersistsCdcIdlePollMs() throws Exception {
        stubDetail(DatabaseType.ORACLE);

        mvc.perform(configPost("POLLING").param("cdcIdlePollMs", "250"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), eq("POLLING"));
        assertThat(captor.getValue().getCdcIdlePollMs()).isEqualTo(250L);
    }

    @Test
    void saveConfigTreatsMissingIdlePollAsEngineDefault() throws Exception {
        stubDetail(DatabaseType.ORACLE);

        // 不带该参数(旧表单/直接 API):落库 null,由消费端回退各引擎默认值。
        mvc.perform(configPost("POLLING"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), eq("POLLING"));
        assertThat(captor.getValue().getCdcIdlePollMs()).isNull();
    }

    @Test
    void saveConfigRejectsIdlePollBelowTheSafetyFloor() throws Exception {
        stubDetail(DatabaseType.ORACLE);

        MvcResult result = mvc.perform(configPost("POLLING").param("cdcIdlePollMs", "10"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getFlashMap().get("error"))
                .isEqualTo("error.project.cdc.idle.poll.too.small");
        // 拒绝时整份配置都不得落库,避免半写状态。
        verify(projectService, never()).saveConfig(anyLong(), any(), any());
    }

    /** Stubs detail with a stored capture strategy (drives selectedStrategy first paint). */
    private Project stubStrategyDetail(DatabaseType sourceType, CaptureStrategy strategy) {
        Project project = stubDetail(sourceType);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        if (strategy == CaptureStrategy.LOGMINER_CDC
                || strategy == CaptureStrategy.PG_LOGICAL_CDC
                || strategy == CaptureStrategy.SQLSERVER_CDC) {
            config.setCdcIdlePollMs(250L);
        }
        when(projectService.loadConfig(project)).thenReturn(config);
        return project;
    }

    /** The idle-poll card block, from its marker to the end of its number input. */
    private static String idlePollBlock(String page) {
        // 以输入框 id 为锚(上方 HTML 注释里也出现了 data-cdc-visibility 串,
        // 单独用属性串会落到注释上),再回退到所在卡片 <div>,截到 input 标签结束。
        int inputAt = page.indexOf("id=\"cdcIdlePollMs\"");
        assertThat(inputAt).as("idle-poll input").isPositive();
        // 属性的那次出现(注释里也有该串)是输入框前最近的一次;再回退到同一标签的 <div。
        int markerAt = page.lastIndexOf("data-cdc-visibility=\"polling-cdc\"", inputAt);
        int cardStart = page.lastIndexOf("<div", markerAt);
        int inputEnd = page.indexOf(">", inputAt);
        return page.substring(cardStart, inputEnd + 1);
    }

    @Test
    void detailShowsIdlePollOnlyForPollingLogCdcStrategies() throws Exception {
        // 轮询式三引擎:卡片可见、输入可用、项目覆盖值得回填
        stubStrategyDetail(DatabaseType.ORACLE, CaptureStrategy.LOGMINER_CDC);
        String oracle = mvc.perform(get("/projects/1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String block = idlePollBlock(oracle);
        assertThat(block).doesNotContain("is-hidden");
        assertThat(block).contains("name=\"cdcIdlePollMs\"").contains("value=\"250\"");
        assertThat(block).doesNotContain("disabled");
        assertThat(oracle).doesNotContain("??option.cdcIdlePoll");

        // MySQL binlog(推送,不轮询):隐藏且禁用,值不随表单提交
        stubStrategyDetail(DatabaseType.MYSQL, CaptureStrategy.BINLOG_CDC);
        String mysql = mvc.perform(get("/projects/1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String binlogBlock = idlePollBlock(mysql);
        assertThat(binlogBlock).contains("is-hidden", "disabled");

        // 普通增量轮询:同样隐藏
        stubDetail(DatabaseType.MYSQL);
        String polling = mvc.perform(get("/projects/1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(idlePollBlock(polling)).contains("is-hidden", "disabled");
    }

    @Test
    void detailHidesCdcCardForPostgres() throws Exception {
        stubDetail(DatabaseType.POSTGRESQL);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("value=\"BINLOG_CDC\"");
        assertThat(page).contains("value=\"POLLING\"", "value=\"FULL_COMPARE\"");

        stubDetail(DatabaseType.MYSQL);

        String mysqlPage = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mysqlPage).contains("value=\"BINLOG_CDC\"");
    }

    @Test
    void newMysqlProjectWithPassingProbePreselectsCdc() throws Exception {
        stubDetail(DatabaseType.MYSQL);
        CdcProbeResult passing = CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.VERSION, true, "MySQL 8.0.36"),
                CdcProbeResult.Check.of(CdcProbeResult.SERVER_ID, true, "server_id=7")));
        // 同一 stub 连续两段:先探测通过(默认选中 CDC),再探测抛异常(绝不阻断渲染)。
        when(cdcOperationsService.probeProject(1L))
                .thenReturn(passing)
                .thenThrow(new RuntimeException("probe blew up"));

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-original-strategy=\"BINLOG_CDC\"");
        assertThat(cardOf(page, "BINLOG_CDC")).contains("picked").contains("checked");
        assertThat(cardOf(page, "POLLING")).doesNotContain("picked").doesNotContain("checked");
        assertThat(page).contains("badge-recommend");

        String degraded = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(degraded).contains("data-original-strategy=\"POLLING\"");
        assertThat(cardOf(degraded, "POLLING")).contains("picked").contains("checked");
        assertThat(cardOf(degraded, "BINLOG_CDC")).doesNotContain("picked").doesNotContain("checked");
        assertThat(degraded).doesNotContain("badge-recommend");
    }

    @Test
    void explicitlySavedPollingStaysPollingEvenWhenCdcProbePasses() throws Exception {
        // 回归:用户显式保存 POLLING(raw 字段已写值)后,即便 binlog 探测通过、
        // 且尚未跑过同步,也不得再把 CDC 推荐回选——否则轮询项目永远停不住轮询。
        stubStrategyDetail(DatabaseType.MYSQL, CaptureStrategy.POLLING);
        CdcProbeResult passing = CdcProbeResult.of(List.of(
                CdcProbeResult.Check.of(CdcProbeResult.VERSION, true, "MySQL 8.0.36")));
        when(cdcOperationsService.probeProject(1L)).thenReturn(passing);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-original-strategy=\"POLLING\"");
        assertThat(cardOf(page, "POLLING")).contains("picked").contains("checked");
        assertThat(cardOf(page, "BINLOG_CDC")).doesNotContain("picked").doesNotContain("checked");
        assertThat(page).doesNotContain("badge-recommend");
        // 显式 POLLING 项目绝不该去碰 binlog 探测(性能 + 语义双重理由)。
        verify(cdcOperationsService, never()).probeProject(anyLong());
    }

    // —— CDC 面板轻量片段端点(5 秒刷新的新目标)+ 停用项目重建基线提示 ——

    @Test
    void cdcCardFragmentRendersTheCardWithoutTouchingTheSourceDatabase() throws Exception {
        stubCdcDetail(true);

        String fragment = mvc.perform(get("/projects/1/cdc-card"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 片段就是卡片本身:带 id(bindCdcRefresh 靠它取卡)和自己的刷新 URL;
        // 不含整页布局,也不含整页模型才有的策略卡片。
        assertThat(fragment).contains("id=\"cdc-card\"");
        assertThat(fragment).contains("data-refresh-url=\"/projects/1/cdc-card\"");
        assertThat(fragment).doesNotContain("<html");
        assertThat(fragment).doesNotContain("value=\"BINLOG_CDC\"");
        // 端点存在的意义就是放过源库:对象列举与就绪探测绝不重跑。
        verify(projectService, never()).listSourceObjects(anyLong());
        verify(cdcOperationsService, never()).probeProject(anyLong());
    }

    @Test
    void cdcCardFragmentIsEmptyForNonCdcProject() throws Exception {
        stubDetail(DatabaseType.MYSQL);   // 默认 POLLING

        String fragment = mvc.perform(get("/projects/1/cdc-card"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // th:if 为假 → 空片段(与整页上卡片缺席镜像一致);前端取不到 #cdc-card
        // 会重载整页,由详情路由展示真实状态。
        assertThat(fragment).doesNotContain("cdc-card");
        verify(projectService, never()).listSourceObjects(anyLong());
    }

    @Test
    void rebaselineHintShowsOnlyForDisabledCdcProject() throws Exception {
        stubCdcDetail(false);
        String disabledPage = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(disabledPage).contains("id=\"cdc-rebaseline-disabled-hint\"");

        stubCdcDetail(true);
        String enabledPage = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(enabledPage).doesNotContain("id=\"cdc-rebaseline-disabled-hint\"");
    }
}
