package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * 同步选项开关随源库对象清单收敛:某一类对象成功列举且确认为空时,对应开关必须默认关闭
 * 并置灰禁用(视图/存过开着却没有可选对象时,保存会被服务端「至少选择一个」校验拦下,
 * 默认状态不该把用户送进这个死胡同);对象列举失败属于临时状况,保持已保存配置不动。
 *
 * <p>搭法照抄 ProjectControllerStrategyTest;断言落在具体 input 标签的 checked/disabled
 * 属性上,不依赖渲染语言。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectObjectOptionsViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    /** 与 ProjectControllerStrategyTest.stubDetail(null) 等价的最小详情页桩:无数据源,
     *  因此不会触发 CDC 探测;SyncConfig 全默认(五个开关皆 true)。 */
    private void stubDetail(ProjectService.SourceObjects objects) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(objects);
    }

    private ProjectService.SourceObjects objectsWith(java.util.List<String> tables,
                                                    java.util.List<String> views,
                                                    java.util.List<String> procedures) {
        ProjectService.SourceObjects objects = new ProjectService.SourceObjects();
        objects.getTables().addAll(tables);
        objects.getViews().addAll(views);
        objects.getProcedures().addAll(procedures);
        return objects;
    }

    private String renderDetail() throws Exception {
        return mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 提取 name="x" 所属的那一个 <input ...> 标签。 */
    private static String inputNamed(String page, String name) {
        int at = page.indexOf("name=\"" + name + "\"");
        assertThat(at).as("input %s exists", name).isPositive();
        int start = page.lastIndexOf("<input", at);
        int end = page.indexOf(">", at);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(at);
        return page.substring(start, end + 1);
    }

    private static void assertCheckedAndEnabled(String page, String name) {
        String tag = inputNamed(page, name);
        assertThat(tag).as("%s should be checked", name).contains("checked=\"checked\"");
        assertThat(tag).as("%s should be enabled", name).doesNotContain("disabled");
    }

    private static void assertUncheckedAndDisabled(String page, String name) {
        String tag = inputNamed(page, name);
        assertThat(tag).as("%s should be unchecked", name).doesNotContain("checked");
        assertThat(tag).as("%s should be disabled", name).contains("disabled=\"disabled\"");
    }

    @Test
    void emptyObjectCategoriesTurnTheirSwitchesOffAndDisabled() throws Exception {
        // 用户截图场景:表/视图/存过全部为 0。
        stubDetail(objectsWith(List.of(), List.of(), List.of()));

        String page = renderDetail();

        assertUncheckedAndDisabled(page, "syncStructure");
        assertUncheckedAndDisabled(page, "syncData");
        assertUncheckedAndDisabled(page, "syncIndexes");
        assertUncheckedAndDisabled(page, "syncViews");
        assertUncheckedAndDisabled(page, "syncProcedures");
    }

    @Test
    void onlyEmptyCategoriesAreGated() throws Exception {
        // 有表、无视图、无存过:表相关三开关保持配置态可用,视图/存过独立收敛。
        stubDetail(objectsWith(List.of("ORDERS"), List.of(), List.of()));

        String page = renderDetail();

        assertCheckedAndEnabled(page, "syncStructure");
        assertCheckedAndEnabled(page, "syncData");
        assertCheckedAndEnabled(page, "syncIndexes");
        assertUncheckedAndDisabled(page, "syncViews");
        assertUncheckedAndDisabled(page, "syncProcedures");
    }

    @Test
    void presentCategoriesKeepSavedConfigState() throws Exception {
        stubDetail(objectsWith(List.of("ORDERS"), List.of("V_ORDERS"), List.of("P_NIGHTLY")));

        String page = renderDetail();

        // 默认配置五个开关皆 true:有对象时一个都不该被页面层改动。
        assertCheckedAndEnabled(page, "syncStructure");
        assertCheckedAndEnabled(page, "syncData");
        assertCheckedAndEnabled(page, "syncIndexes");
        assertCheckedAndEnabled(page, "syncViews");
        assertCheckedAndEnabled(page, "syncProcedures");
    }

    @Test
    void objectListingFailureKeepsSwitchesUntouched() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(new SyncConfig());
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        // 源库临时连不上:不知道有什么,绝不能替用户把开关抹掉。
        when(projectService.listSourceObjects(1L))
                .thenThrow(new RuntimeException("source unreachable"));

        String page = renderDetail();

        assertCheckedAndEnabled(page, "syncStructure");
        assertCheckedAndEnabled(page, "syncData");
        assertCheckedAndEnabled(page, "syncIndexes");
        assertCheckedAndEnabled(page, "syncViews");
        assertCheckedAndEnabled(page, "syncProcedures");
    }
}
