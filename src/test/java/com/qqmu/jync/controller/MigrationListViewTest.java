package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
class MigrationListViewTest {

    @Autowired private MockMvc mvc;
    @MockBean private MigrationTaskService taskService;
    @MockBean private MigrationConnectionService connectionService;
    @MockBean private MigrationTypeRegistry registry;
    @MockBean private MigrationRunRepository runRepository;

    @Test
    void theListShowsEachTaskAndACancelButtonOnlyForARunningOne() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);
        when(taskService.findByType(MigrationType.ES_TO_EASYSEARCH))
                .thenReturn(List.of(task(9L, "nightly", "0 0 3 * * *"),
                        task(10L, "idle", "0 0 4 * * *")));
        when(taskService.isRunning(9L)).thenReturn(true);
        when(taskService.isRunning(10L)).thenReturn(false);

        String page = mvc.perform(get("/migrations/es-to-easysearch").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"migration-tasks\"")
                .contains("nightly").contains("0 0 3 * * *")
                .contains("data-role=\"cancel-run\"")
                .contains("/migrations/es-to-easysearch/9/cancel")
                .contains("/migrations/es-to-easysearch/10/run")
                .contains("/migrations/es-to-easysearch/9/edit");
    }

    @Test
    void aViewerGetsTheRowsButNoMutatingForms() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);
        when(taskService.findByType(MigrationType.ES_TO_EASYSEARCH))
                .thenReturn(List.of(task(9L, "nightly", "0 0 3 * * *")));
        when(taskService.isRunning(9L)).thenReturn(false);

        String page = mvc.perform(get("/migrations/es-to-easysearch").with(asViewer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("nightly")
                .doesNotContain("/migrations/es-to-easysearch/9/run")
                .doesNotContain("/migrations/es-to-easysearch/9/delete")
                .doesNotContain("/migrations/es-to-easysearch/new");
    }

    @Test
    void anEmptyListExplainsWhatTheTypeDoes() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);
        when(taskService.findByType(MigrationType.ES_TO_EASYSEARCH)).thenReturn(List.of());

        String page = mvc.perform(get("/migrations/es-to-easysearch").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"migration-empty\"");
    }

    private static MigrationTask task(long id, String name, String cron) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setName(name);
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        task.setCronExpression(cron);
        task.setEnabled(true);
        task.setLastRunTime(Instant.parse("2026-11-02T10:00:00Z"));
        task.setLastRunSuccess(true);
        task.setLastRunResult("SUCCESS indices=1/1 read=2 written=2");
        return task;
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
