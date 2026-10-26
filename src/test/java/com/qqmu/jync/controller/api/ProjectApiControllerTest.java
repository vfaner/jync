package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
    void tableColumnsFeedTheCleansingColumnPicker() throws Exception {
        when(projectService.listTableColumns(7L, "orders"))
                .thenReturn(List.of("id", "customer_name", "amount"));

        mvc.perform(get("/api/projects/7/columns").param("table", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.columns[1]").value("customer_name"));
        verify(projectService).listTableColumns(7L, "orders");
    }

    @Test
    void aStoppedManualSyncReturnsTheWarnToastInsteadOfAnErrorDigest() throws Exception {
        // The user pressed 「停止同步」 while 「立即同步」 was running. The round ended with an
        // error entry (that is how the abort is folded), but nothing failed and there is
        // nothing to fix — a red "有失败项" toast plus the raw English abort sentence is a
        // wrong report for the user's own action.
        SyncResult result = new SyncResult();
        result.addError("Sync of project 'p' stopped by request; aborting at the next safe point");
        result.setStopped(true);
        result.setRowsInserted(1200);
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", result);
        when(projectService.syncNow(1L)).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/sync-now").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("msg.sync.stopped"))
                .andExpect(jsonPath("$.toastKind").value("warn"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.rowsInserted").value(1200))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
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
