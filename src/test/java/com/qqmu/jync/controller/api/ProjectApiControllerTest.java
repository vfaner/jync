package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.task.SyncTaskRunner;

/**
 * The JSON contract behind the project control buttons.
 *
 * <p>app.js resolves {@code message} as a bundle key and honours {@code toastKind} /
 * {@code skippedTables}; the force-unlock override additionally has to turn the service's
 * "nothing held" refusal into the shared 409 body instead of an HTML error page.
 * Authorization itself is covered by SecurityConfigTest, so the class signs in as ADMIN.
 */
@WebMvcTest(ProjectApiController.class)
@WithMockUser(roles = "ADMIN")
class ProjectApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;

    @Test
    void forceUnlockReturnsTheWarnToastContract() throws Exception {
        mvc.perform(post("/api/projects/1/force-unlock").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.lock.forced"))
                .andExpect(jsonPath("$.toastKind").value("warn"));
        verify(projectService).forceUnlock(1L);
    }

    @Test
    void forceUnlockOnAFreeLockBecomesA409WithTheRefusalKey() throws Exception {
        doThrow(new IllegalStateException("error.project.lock.not.held"))
                .when(projectService).forceUnlock(1L);

        mvc.perform(post("/api/projects/1/force-unlock").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.lock.not.held"));
    }

    @Test
    void syncNowCarriesSkippedTablesForTheToastLoop() throws Exception {
        SyncResult result = new SyncResult();
        result.addSkipped("LOGS", "no usable cursor column and no primary key");
        // Outcome.executed is package-private by design; reflection keeps the boundary.
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", result);
        when(projectService.syncNow(1L)).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/sync-now").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedTables[0].table").value("LOGS"))
                .andExpect(jsonPath("$.skippedTables[0].reason")
                        .value("no usable cursor column and no primary key"));
    }
}
