package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlBatchTableException;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The batch form is one page for creating a group and for editing its settings afterwards. A
 * table selection that the service refuses comes back on the same form with the offending names,
 * rather than a bare redirect that loses them.
 */
@WebMvcTest(EtlBatchController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlBatchViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EtlBatchService etlBatchService;

    @MockBean
    private EtlTaskService etlTaskService;

    @Test
    void theNewFormRendersConnectionsAndTheTablePicker() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));

        String page = mvc.perform(get("/etl/batches/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"name\"").contains("name=\"sourceDbId\"")
                .contains("name=\"targetDbId\"").contains("data-batch-tables");
    }

    /** Same six-field rule as the single-task form: the hint must be a valid expression. */
    @Test
    void theCronHintShowsTheSixFieldFormTheValidatorAccepts() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));

        String page = mvc.perform(get("/etl/batches/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("placeholder=\"0 0 2 * * *\"");
        assertThat(page).doesNotContain("placeholder=\"0 2 * * *\"");
    }

    @Test
    void createPostsTheChosenTablesAndFlashesTheMessage() throws Exception {
        mvc.perform(post("/etl/batches/create").with(asAdmin()).with(csrf())
                        .param("name", "nightly")
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("tables", "orders")
                        .param("tables", "users"))
                .andExpect(redirectedUrl("/etl"))
                .andExpect(flash().attribute("message", "msg.etl.batch.created"));

        verify(etlBatchService).createWithTables(
                org.mockito.ArgumentMatchers.argThat(batch -> "nightly".equals(batch.getName())
                        && Long.valueOf(1L).equals(batch.getSourceDbId())),
                eq(List.of("orders", "users")));
    }

    @Test
    void aRefusedSelectionComesBackOnTheFormWithTheNames() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));
        when(etlBatchService.createWithTables(any(), any()))
                .thenThrow(new EtlBatchTableException(
                        "error.etl.batch.tables.unknown", List.of("gone")));

        mvc.perform(post("/etl/batches/create").with(asAdmin()).with(csrf())
                        .param("name", "nightly")
                        .param("sourceDbId", "1")
                        .param("targetDbId", "2")
                        .param("tables", "orders")
                        .param("tables", "gone"))
                .andExpect(status().isOk())
                .andExpect(view().name("etl-batch-form"))
                .andExpect(model().attribute("error", "error.etl.batch.tables.unknown"))
                .andExpect(model().attribute("errorDetail", "gone"))
                .andExpect(model().attribute("selectedTables", List.of("orders", "gone")));
    }

    @Test
    void theEditFormLocksTheConnectionsAndOffersTheMemberActions() throws Exception {
        EtlBatch batch = batch(5L);
        EtlTask member = member(7L, "orders");
        when(etlBatchService.findById(5L)).thenReturn(Optional.of(batch));
        when(etlBatchService.members(5L)).thenReturn(List.of(member));
        when(etlBatchService.isRunning(5L)).thenReturn(false);
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));
        when(etlTaskService.isRunning(7L)).thenReturn(false);

        String page = mvc.perform(get("/etl/batches/5/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("type=\"hidden\" name=\"sourceDbId\"")
                .contains("type=\"hidden\" name=\"targetDbId\"")
                .doesNotContain("id=\"sourceDbId\"")
                .contains("/etl/batches/5/add-tables")
                .contains("/etl/batches/5/members/7/remove")
                .contains("orders");
    }

    @Test
    void addingTablesRedirectsBackToTheEditPage() throws Exception {
        mvc.perform(post("/etl/batches/5/add-tables").with(asAdmin()).with(csrf())
                        .param("tables", "people"))
                .andExpect(redirectedUrl("/etl/batches/5/edit"))
                .andExpect(flash().attribute("message", "msg.etl.batch.tables.added"));

        verify(etlBatchService).addTables(5L, List.of("people"));
    }

    @Test
    void removingAMemberFlashesTheRemoval() throws Exception {
        when(etlBatchService.members(5L)).thenReturn(List.of(member(7L, "orders")));

        mvc.perform(post("/etl/batches/5/members/7/remove").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/etl/batches/5/edit"))
                .andExpect(flash().attribute("message", "msg.etl.batch.member.removed"));

        verify(etlBatchService).removeMember(5L, 7L);
    }

    @Test
    void removingTheLastMemberFlashesTheAutoDisable() throws Exception {
        when(etlBatchService.members(5L)).thenReturn(List.of());

        mvc.perform(post("/etl/batches/5/members/7/remove").with(asAdmin()).with(csrf()))
                .andExpect(flash().attribute("message", "msg.etl.batch.member.emptied"));
    }

    @Test
    void saveFlashAndRedirectBackToTheEditPage() throws Exception {
        when(etlBatchService.saveSettings(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/etl/batches/save").with(asAdmin()).with(csrf())
                        .param("id", "5")
                        .param("name", "renamed"))
                .andExpect(redirectedUrl("/etl/batches/5/edit"))
                .andExpect(flash().attribute("message", "msg.etl.batch.saved"));
    }

    @Test
    void runFlashesStartedOrTheRunningError() throws Exception {
        when(etlBatchService.submit(5L)).thenReturn(true);
        mvc.perform(post("/etl/batches/5/run").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/etl"))
                .andExpect(flash().attribute("message", "msg.etl.batch.started"));

        when(etlBatchService.submit(6L)).thenReturn(false);
        mvc.perform(post("/etl/batches/6/run").with(asAdmin()).with(csrf()))
                .andExpect(flash().attribute("error", "error.etl.batch.running"));
    }

    @Test
    void startStopAndDeleteFlashTheirKeys() throws Exception {
        mvc.perform(post("/etl/batches/5/start").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/etl"))
                .andExpect(flash().attribute("message", "msg.etl.batch.enabled"));
        verify(etlBatchService).setEnabled(5L, true);

        mvc.perform(post("/etl/batches/5/stop").with(asAdmin()).with(csrf()))
                .andExpect(flash().attribute("message", "msg.etl.batch.disabled"));
        verify(etlBatchService).setEnabled(5L, false);

        mvc.perform(post("/etl/batches/5/delete").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/etl"))
                .andExpect(flash().attribute("message", "msg.etl.batch.deleted"));
        verify(etlBatchService).delete(5L);
    }

    @Test
    void aServiceFailureFlashesTheErrorKey() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("error.etl.batch.running"))
                .when(etlBatchService).delete(5L);

        mvc.perform(post("/etl/batches/5/delete").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/etl"))
                .andExpect(flash().attribute("error", "error.etl.batch.running"));
    }

    private static DatabaseConfig source(Long id) {
        return config(id, ConnectionRole.SOURCE);
    }

    private static DatabaseConfig target(Long id) {
        return config(id, ConnectionRole.TARGET);
    }

    private static DatabaseConfig config(Long id, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName("db" + id);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        return config;
    }

    private static EtlBatch batch(Long id) {
        EtlBatch batch = new EtlBatch();
        batch.setId(id);
        batch.setName("nightly");
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        batch.setCronExpression("0 0 3 * * *");
        return batch;
    }

    private static EtlTask member(Long id, String targetTable) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName(targetTable);
        task.setBatchId(5L);
        task.setTargetTable(targetTable);
        return task;
    }

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities()));
    }
}
