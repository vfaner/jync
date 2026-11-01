package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The ETL list is now two lists: batch cards whose members run together, and the standalone
 * tasks that keep their own schedule. A member belongs to exactly one of them.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlListViewTest {

    private static final String BATCH_MARKER = "data-role=\"etl-batches\"";
    private static final String STANDALONE_MARKER = "data-role=\"standalone-tasks\"";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EtlTaskService etlTaskService;

    @MockBean
    private EtlPrecheckService precheckService;

    @MockBean
    private EtlBatchService etlBatchService;

    @Test
    void aBatchCardShowsItsTablesAndMembersStayOutOfTheStandaloneList() throws Exception {
        EtlTask member = task(7L, "orders-member", 5L, "orders");
        EtlTask lone = task(9L, "lone-task", null, "people");
        EtlBatch batch = batch(5L, "nightly");
        when(etlTaskService.findAll()).thenReturn(List.of(member, lone));
        when(etlTaskService.connections()).thenReturn(List.of());
        when(etlTaskService.isRunning(7L)).thenReturn(false);
        when(etlTaskService.isRunning(9L)).thenReturn(false);
        when(etlBatchService.findAll()).thenReturn(List.of(batch));
        when(etlBatchService.isRunning(5L)).thenReturn(false);

        String page = mvc.perform(get("/etl").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String batches = section(page, BATCH_MARKER, STANDALONE_MARKER);
        String standalone = page.substring(page.indexOf(STANDALONE_MARKER));
        assertThat(batches).contains("nightly").contains("orders-member").contains("0 0 3 * * *");
        assertThat(standalone).contains("lone-task").doesNotContain("orders-member");
    }

    @Test
    void aViewerSeesTheBatchesButNoActionForms() throws Exception {
        when(etlTaskService.findAll()).thenReturn(List.of());
        when(etlTaskService.connections()).thenReturn(List.of());
        when(etlBatchService.findAll()).thenReturn(List.of(batch(5L, "nightly")));
        when(etlBatchService.isRunning(5L)).thenReturn(false);

        String page = mvc.perform(get("/etl").with(asViewer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("nightly").doesNotContain("/etl/batches/5/run")
                .doesNotContain("/etl/batches/5/delete")
                .doesNotContain("href=\"/etl/batches/new\"");
    }

    private static String section(String page, String startMarker, String endMarker) {
        int start = page.indexOf(startMarker);
        assertThat(start).as("section %s rendered", startMarker).isNotNegative();
        int end = page.indexOf(endMarker, start);
        assertThat(end).as("section %s rendered", endMarker).isGreaterThan(start);
        return page.substring(start, end);
    }

    private static EtlTask task(Long id, String name, Long batchId, String targetTable) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName(name);
        task.setBatchId(batchId);
        task.setTargetTable(targetTable);
        return task;
    }

    private static EtlBatch batch(Long id, String name) {
        EtlBatch batch = new EtlBatch();
        batch.setId(id);
        batch.setName(name);
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        batch.setCronExpression("0 0 3 * * *");
        batch.setEnabled(true);
        return batch;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal(UserRole.ADMIN));
    }

    private static RequestPostProcessor asViewer() {
        return authentication(principal(UserRole.VIEWER));
    }

    private static UsernamePasswordAuthenticationToken principal(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
    }
}
