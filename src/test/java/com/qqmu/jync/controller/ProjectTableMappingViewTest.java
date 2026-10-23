package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
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
 * 同步对象面板的「源表 → 目标表」行：左侧勾选框、中间虚线箭头、右侧目标表名输入框。
 * 输入框留空即与源表同名，所以已有映射要显示出来，未映射的表用源表名做 placeholder；
 * 每行还带一个隐藏的 mappingSource，和输入框按文档顺序配对提交。
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectTableMappingViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private void stubDetail(List<String> tables, SyncConfig config) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        ProjectService.SourceObjects objects = new ProjectService.SourceObjects();
        objects.getTables().addAll(tables);
        when(projectService.listSourceObjects(1L)).thenReturn(objects);
    }

    private String renderDetail() throws Exception {
        return mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The one input tag carrying the given attribute fragment. */
    private static String inputWith(String page, String marker) {
        int at = page.indexOf(marker);
        assertThat(at).as("marker %s exists", marker).isPositive();
        int start = page.lastIndexOf("<input", at);
        int end = page.indexOf('>', at);
        return page.substring(start, end + 1);
    }

    private static int count(String haystack, String needle) {
        int hits = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            hits++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return hits;
    }

    @Test
    void eachTableGetsATargetInputThatShowsItsCurrentMapping() throws Exception {
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);
        config.setTables(new LinkedHashSet<>(List.of("orders", "products")));
        config.getTableNameMapping().put("orders", "orders_copy");
        stubDetail(List.of("orders", "products"), config);

        String page = renderDetail();

        String mapped = inputWith(page, "data-source=\"orders\"");
        assertThat(mapped).contains("placeholder=\"orders\"");
        assertThat(mapped).contains("value=\"orders_copy\"");
        assertThat(mapped).contains("class=\"input map-target\"");

        // Unmapped: the field is empty and the source name is only the placeholder, so
        // saving an untouched form keeps "target = source".
        String unmapped = inputWith(page, "data-source=\"products\"");
        assertThat(unmapped).contains("placeholder=\"products\"");
        assertThat(unmapped).contains("value=\"\"");
    }

    @Test
    void everyTableRowCarriesAPairedHiddenSourceAndADashedArrow() throws Exception {
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);
        config.setTables(new LinkedHashSet<>(List.of("orders", "products")));
        stubDetail(List.of("orders", "products"), config);

        String page = renderDetail();

        assertThat(count(page, "name=\"mappingSource\"")).isEqualTo(2);
        assertThat(count(page, "name=\"mappingTarget\"")).isEqualTo(2);
        assertThat(count(page, "class=\"map-arrow\"")).isEqualTo(2);
        assertThat(page).contains("name=\"mappingSource\" value=\"orders\"");
        assertThat(page).contains("name=\"mappingSource\" value=\"products\"");
    }
}
