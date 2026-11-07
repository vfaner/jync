package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.migration.MigrationTypeRegistry;

@WebMvcTest(MigrationController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class MigrationHomeViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private MigrationTaskService taskService;
    @MockitoBean private MigrationConnectionService connectionService;
    @MockitoBean private MigrationTypeRegistry registry;
    @MockitoBean private com.qqmu.jync.repository.MigrationRunRepository runRepository;

    @Test
    void theLandingPageListsEveryTypeThatHasAHandler() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);
        when(registry.isRegistered(MigrationType.MONGODB_TO_MONGODB)).thenReturn(true);
        when(registry.isRegistered(MigrationType.MONGODB_TO_KINGBASE)).thenReturn(true);
        MigrationTask task = new MigrationTask();
        task.setId(9L);
        when(taskService.findByType(MigrationType.ES_TO_EASYSEARCH)).thenReturn(List.of(task));

        String page = mvc.perform(get("/migrations").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"type-grid\"")
                .contains("href=\"/migrations/es-to-easysearch\"")
                .contains("href=\"/migrations/mongodb-to-mongodb\"")
                .contains("href=\"/migrations/mongodb-to-kingbase\"")
                .contains("href=\"/migrations/connections\"")
                .doesNotContain("data-role=\"type-coming-soon\"");
    }

    @Test
    void aViewerSeesTheCardsButNoConnectionAdministrationLink() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);

        String page = mvc.perform(get("/migrations").with(asViewer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("href=\"/migrations/es-to-easysearch\"")
                .doesNotContain("href=\"/migrations/connections\"");
    }

    @Test
    void theMigrationPagesLightTheSyncTasksGroupInTheNav() throws Exception {
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);

        String page = mvc.perform(get("/migrations").locale(java.util.Locale.ENGLISH)
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).containsPattern("nav-links-group is-active[\\s\\S]{0,800}Sync tasks");
        // Exactly one group is highlighted; the migration page belongs to the sync group and
        // not to configuration or logs.
        assertThat(page.split("nav-links-group is-active", -1)).hasSize(2);
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
