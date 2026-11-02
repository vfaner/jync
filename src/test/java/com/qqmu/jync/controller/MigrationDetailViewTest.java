package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.migration.MigrationTypeRegistry;

@WebMvcTest(MigrationController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class MigrationDetailViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private MigrationTaskService taskService;
    @MockBean private MigrationConnectionService connectionService;
    @MockBean private MigrationTypeRegistry registry;
    @MockBean private MigrationRunRepository runRepository;

    @Test
    void theDetailShowsTheTaskItsOptionsAndTheLastFiftyRuns() throws Exception {
        MigrationTask task = task();
        when(taskService.findById(9L)).thenReturn(Optional.of(task));
        when(taskService.isRunning(9L)).thenReturn(false);
        when(runRepository.findTop50ByMigrationTaskIdOrderByStartedAtDesc(9L))
                .thenReturn(List.of(run(MigrationRunStatus.FAILED,
                        "orders-2026: bulk failed: id=7 mapper_parsing_exception")));
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);

        String page = mvc.perform(get("/migrations/es-to-easysearch/9").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("nightly")
                .contains("data-role=\"run-history\"")
                .contains("FAILED")
                .contains("bulk failed: id=7")
                .contains("orders-*")
                .contains("0 0 3 * * *");
    }

    @Test
    void aRunningTaskOffersCancelInsteadOfRun() throws Exception {
        when(taskService.findById(9L)).thenReturn(Optional.of(task()));
        when(taskService.isRunning(9L)).thenReturn(true);
        when(runRepository.findTop50ByMigrationTaskIdOrderByStartedAtDesc(9L)).thenReturn(List.of());
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);

        String page = mvc.perform(get("/migrations/es-to-easysearch/9").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"cancel-run\"")
                .contains("/migrations/es-to-easysearch/9/cancel")
                .doesNotContain("/migrations/es-to-easysearch/9/run\"");
    }

    @Test
    void anUnknownTaskGoesBackToTheList() throws Exception {
        when(taskService.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/migrations/es-to-easysearch/99").with(asAdmin()))
                .andExpect(status().is3xxRedirection());
    }

    private static MigrationTask task() {
        MigrationTask task = new MigrationTask();
        task.setId(9L);
        task.setName("nightly");
        task.setDescription("copy the order indices");
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        task.setOptionsJson("{\"indicesText\":\"orders-*\",\"writeMode\":\"OVERWRITE\"}");
        task.setCronExpression("0 0 3 * * *");
        task.setEnabled(true);
        task.setLastRunTime(Instant.parse("2026-11-02T10:00:00Z"));
        task.setLastRunSuccess(false);
        task.setLastRunResult("FAILED indices=1/2 read=10 written=7");
        return task;
    }

    private static MigrationRun run(MigrationRunStatus status, String message) {
        MigrationRun run = new MigrationRun();
        run.setId(3L);
        run.setMigrationTaskId(9L);
        run.setStatus(status);
        run.setTriggerType(MigrationRunTrigger.MANUAL);
        run.setStartedAt(Instant.parse("2026-11-02T10:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-11-02T10:00:04Z"));
        run.setIndicesTotal(2);
        run.setIndicesSucceeded(1);
        run.setIndicesFailed(1);
        run.setDocumentsRead(10);
        run.setDocumentsWritten(7);
        run.setMessage(message);
        return run;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal(UserRole.ADMIN));
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
