package com.qqmu.jync.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class MigrationActionGuardTest {

    @Autowired private MockMvc mvc;
    @MockBean private MigrationTaskService taskService;
    @MockBean private MigrationConnectionService connectionService;
    @MockBean private MigrationTypeRegistry registry;
    @MockBean private MigrationRunRepository runRepository;

    @Test
    void aTaskOfAnotherTypeIsNeverRunCancelledToggledOrDeletedFromThisRoute() throws Exception {
        when(taskService.findById(9L))
                .thenReturn(Optional.of(task(MigrationType.MONGODB_TO_MONGODB)));
        when(registry.isRegistered(MigrationType.ES_TO_EASYSEARCH)).thenReturn(true);

        for (String action : new String[] {"run", "cancel", "enable", "disable", "delete"}) {
            mvc.perform(post("/migrations/es-to-easysearch/9/" + action)
                            .with(asAdmin()).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/migrations/es-to-easysearch"));
        }

        verify(taskService, never()).submit(any(), any());
        verify(taskService, never()).cancel(any());
        verify(taskService, never()).setEnabled(any(), anyBoolean());
        verify(taskService, never()).delete(any());
    }

    @Test
    void aMissingTaskIsNeverSubmittedAndLeavesNoRunRowBehind() throws Exception {
        when(taskService.findById(9L)).thenReturn(Optional.empty());

        mvc.perform(post("/migrations/es-to-easysearch/9/run").with(asAdmin()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/migrations/es-to-easysearch"))
                .andExpect(flash().attribute("error", "error.migration.not.found"));

        verify(taskService, never()).submit(any(), any());
    }

    private static MigrationTask task(MigrationType type) {
        MigrationTask task = new MigrationTask();
        task.setId(9L);
        task.setName("nightly");
        task.setType(type);
        task.setSourceConnectionId(1L);
        task.setTargetConnectionId(2L);
        return task;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal());
    }

    private static UsernamePasswordAuthenticationToken principal() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
    }
}
