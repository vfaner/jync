package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The column-cleansing editor: a closed rule set, saved through its own small endpoint so
 * the main configuration form is untouched. Saving a rule on a running project is refused
 * for the same reason any other configuration change is.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ProjectColumnRuleViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private SyncConfig storedConfig(boolean enabled) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(enabled);
        SyncConfig config = new SyncConfig();
        when(projectService.findById(1L)).thenReturn(Optional.of(project));
        when(projectService.loadConfig(project)).thenReturn(config);
        when(databaseConfigService.findByRole(any())).thenReturn(List.of());
        when(databaseConfigService.findById(any())).thenReturn(Optional.empty());
        return config;
    }

    @Test
    void addingARuleAppendsItAndSavesTheProject() throws Exception {
        SyncConfig config = storedConfig(false);

        mvc.perform(post("/projects/1/column-rules").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("columnName", "customer_name")
                        .param("ruleType", "TRIM")
                        .param("ruleArg", "")
                        .param("ruleReplacement", ""))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<SyncConfig> saved = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), saved.capture());
        assertThat(saved.getValue().getColumnRules()).hasSize(1);
        assertThat(saved.getValue().getColumnRules().get(0).getTable()).isEqualTo("orders");
        assertThat(saved.getValue().getColumnRules().get(0).getType())
                .isEqualTo(ColumnRuleType.TRIM);
        assertThat(config.getColumnRules()).hasSize(1);
    }

    @Test
    void aRuleWithAnInvalidRegexIsRefusedWithoutSaving() throws Exception {
        storedConfig(false);

        mvc.perform(post("/projects/1/column-rules").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("columnName", "phone")
                        .param("ruleType", "REGEX_REPLACE")
                        .param("ruleArg", "[unclosed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "error.columnrule.regex.invalid"));

        verify(projectService, never()).saveConfig(anyLong(), any());
    }

    @Test
    void aRuleMissingItsArgumentIsRefusedWithoutSaving() throws Exception {
        storedConfig(false);

        mvc.perform(post("/projects/1/column-rules").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("columnName", "code")
                        .param("ruleType", "STRIP_PREFIX")
                        .param("ruleArg", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "error.columnrule.arg.required"));

        verify(projectService, never()).saveConfig(anyLong(), any());
    }

    @Test
    void aRunningProjectMustBeStoppedFirst() throws Exception {
        storedConfig(true);

        mvc.perform(post("/projects/1/column-rules").with(asAdmin()).with(csrf())
                        .param("tableName", "orders")
                        .param("columnName", "name")
                        .param("ruleType", "TRIM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "error.project.stop.before.config"));

        verify(projectService, never()).saveConfig(anyLong(), any());
    }

    @Test
    void deletingARuleRemovesItByIndex() throws Exception {
        SyncConfig config = storedConfig(false);
        config.getColumnRules().addAll(List.of(
                rule("orders", "name", ColumnRuleType.TRIM, null),
                rule("orders", "phone", ColumnRuleType.UPPER, null)));

        mvc.perform(post("/projects/1/column-rules/delete").with(asAdmin()).with(csrf())
                        .param("index", "0"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<SyncConfig> saved = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), saved.capture());
        assertThat(saved.getValue().getColumnRules()).hasSize(1);
        assertThat(saved.getValue().getColumnRules().get(0).getColumn()).isEqualTo("phone");
    }

    @Test
    void theDetailPageListsTheStoredRulesWithTheAddForm() throws Exception {
        SyncConfig config = storedConfig(false);
        config.getColumnRules().add(rule("orders", "customer_name", ColumnRuleType.TRIM, null));

        String page = renderDetail();
        assertThat(page).contains("customer_name");
        // The rule label, not the raw enum name, is what the user reads.
        assertThat(page).contains("Trim spaces");
        assertThat(page).contains("action=\"/projects/1/column-rules\"");
        // Delete is submitted by position; `${i}` on a th:each status renders the whole
        // IterationStatus object, which the int parameter rejects with a 400 page.
        assertThat(page).contains("name=\"index\" value=\"0\"");
    }

    /**
     * The add form must offer the table's columns instead of a free-text box, and the
     * argument fields must be driven by the selected rule: keeping them always visible
     * made "argument" and "replacement" look like they apply to TRIM/UPPER too.
     */
    @Test
    void theAddFormHooksTheColumnPickerAndRuleSpecificArgumentFields() throws Exception {
        storedConfig(false);

        String page = renderDetail();
        assertThat(page).contains("data-role=\"column-rule-form\"");
        // Column input: datalist id plus the endpoint the focus handler calls.
        assertThat(page).contains("list=\"column-rule-columns\"");
        assertThat(page).contains("data-action=\"/api/projects/1/columns\"");
        // Only the rules that take an argument carry a label for it.
        assertThat(page).contains("data-arg-label=\"Prefix to remove\"");
        assertThat(page).contains("data-arg-label=\"Regular expression\"");
        // The submit button gets its own right-aligned row instead of trailing the grid.
        assertThat(page).contains("class=\"column-rule-actions\"");
    }

    private String renderDetail() throws Exception {
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(new ArrayList<>());
        when(projectService.findFullLoadProgress(1L)).thenReturn(java.util.Map.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());

        MvcResult result = mvc.perform(get("/projects/1").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static ColumnRule rule(String table, String column, ColumnRuleType type, String arg) {
        ColumnRule rule = new ColumnRule();
        rule.setTable(table);
        rule.setColumn(column);
        rule.setType(type);
        rule.setArg(arg);
        return rule;
    }
}
