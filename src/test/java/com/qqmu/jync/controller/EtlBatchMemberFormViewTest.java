package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * Editing a member task: the batch owns its connections and schedule, so the form shows the
 * fixed pair as read-only hidden values and offers no cron field of its own. Everything that
 * belongs to the table — SQL, target table, write mode, timeout, precheck — stays editable.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlBatchMemberFormViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EtlTaskService etlTaskService;

    @MockBean
    private EtlPrecheckService precheckService;

    @MockBean
    private EtlBatchService etlBatchService;

    @Test
    void aMemberFormShowsTheBatchAndLocksItsConnections() throws Exception {
        EtlTask member = task(7L, 5L);
        when(etlTaskService.findById(7L)).thenReturn(Optional.of(member));
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));
        when(etlTaskService.isRunning(7L)).thenReturn(false);
        when(etlBatchService.findById(5L)).thenReturn(Optional.of(batch(5L, "nightly")));

        String page = mvc.perform(get("/etl/7/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"batch-banner\"")
                // The banner already says the batch owns it; a "put these tables in a batch"
                // hint here would ask the member to join the batch it is already in.
                .doesNotContain("data-role=\"batch-hint\"")
                .contains("nightly")
                .contains("href=\"/etl/batches/5/edit\"")
                .contains("type=\"hidden\" name=\"sourceDbId\"")
                .contains("type=\"hidden\" name=\"targetDbId\"")
                .doesNotContain("id=\"sourceDbId\"")
                .doesNotContain("id=\"targetDbId\"")
                .doesNotContain("name=\"cronExpression\"")
                .contains("formaction=\"/etl/precheck\"");
    }

    @Test
    void aStandaloneFormKeepsItsEditableConnectionsAndCron() throws Exception {
        EtlTask standalone = task(9L, null);
        when(etlTaskService.findById(9L)).thenReturn(Optional.of(standalone));
        when(etlTaskService.connections()).thenReturn(List.of(source(1L), target(2L)));
        when(etlTaskService.isRunning(9L)).thenReturn(false);

        String page = mvc.perform(get("/etl/9/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("data-role=\"batch-banner\"")
                .contains("data-role=\"batch-hint\"")
                .contains("id=\"sourceDbId\"")
                .contains("id=\"targetDbId\"")
                .contains("name=\"cronExpression\"");
    }

    private static EtlTask task(Long id, Long batchId) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setName("orders");
        task.setBatchId(batchId);
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("select * from orders");
        task.setTargetTable("orders");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static EtlBatch batch(Long id, String name) {
        EtlBatch batch = new EtlBatch();
        batch.setId(id);
        batch.setName(name);
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        return batch;
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
