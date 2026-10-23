package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * 同步对象面板里每张表都带一行「源表 → 目标表名」：隐藏的 {@code mappingSource} 与输入框
 * {@code mappingTarget} 按文档顺序成对提交。这里钉住三条边界：
 * <ul>
 *   <li>成对的行要成为保存后的映射；</li>
 *   <li>没勾选的表即使输入框里还有旧文字，也不能变成映射；</li>
 *   <li>源库列举失败/旧客户端没有提交映射字段时，已存映射必须原样保留，不能被空提交清空。</li>
 * </ul>
 * 空格与留空的规范化由 {@code ProjectService.saveConfig} 统一兜底（见 ProjectServiceTest）。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectControllerTableMappingTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private SyncConfig stubConfig() {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        return config;
    }

    private SyncConfig savedConfig() {
        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), isNull());
        return captor.getValue();
    }

    @Test
    void mappingRowsAreSavedForTheirCheckedSourceTables() throws Exception {
        stubConfig();

        mvc.perform(post("/projects/1/config").with(csrf())
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders", "products")
                        .param("mappingSource", "orders", "products")
                        .param("mappingTarget", "orders_copy", "products_archive"))
                .andExpect(status().is3xxRedirection());

        assertThat(savedConfig().getTableNameMapping())
                .containsEntry("orders", "orders_copy")
                .containsEntry("products", "products_archive");
    }

    @Test
    void aRowWhoseTableIsUncheckedIsNotSavedAsAMapping() throws Exception {
        stubConfig();

        // products 的表单行仍然渲染（输入框里可能留着旧值），但它没被勾选：
        // 不同步的表不存在目标表，存下映射只会让下次重新勾选时悄悄沿用旧名字。
        mvc.perform(post("/projects/1/config").with(csrf())
                        .param("objectSelectionExplicit", "true")
                        .param("tables", "orders")
                        .param("mappingSource", "orders", "products")
                        .param("mappingTarget", "orders_copy", "products_archive"))
                .andExpect(status().is3xxRedirection());

        assertThat(savedConfig().getTableNameMapping())
                .containsExactly(java.util.Map.entry("orders", "orders_copy"));
    }

    @Test
    void aMissingMappingPayloadKeepsTheStoredMapping() throws Exception {
        SyncConfig stored = stubConfig();
        stored.getTableNameMapping().put("orders", "orders_legacy");

        // 源库列举失败时面板不渲染，mappingSource/mappingTarget 都不会提交；
        // 这不是「把映射删光」，已存映射必须原样留下。
        mvc.perform(post("/projects/1/config").with(csrf())
                        .param("syncStructure", "true")
                        .param("syncData", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(savedConfig().getTableNameMapping())
                .containsExactly(java.util.Map.entry("orders", "orders_legacy"));
    }
}
