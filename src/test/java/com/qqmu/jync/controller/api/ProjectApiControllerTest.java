package com.qqmu.jync.controller.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @MockitoBean private ProjectService projectService;

    @Test
    void forceUnlockReturnsTheWarnToastContract() throws Exception {
        mvc.perform(post("/api/projects/1/force-unlock").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.lock.forced"))
                .andExpect(jsonPath("$.toastKind").value("warn"));
        verify(projectService).forceUnlock(eq(1L), any());
    }

    @Test
    void forceUnlockOnAFreeLockBecomesA409WithTheRefusalKey() throws Exception {
        doThrow(new IllegalStateException("error.project.lock.not.held"))
                .when(projectService).forceUnlock(eq(1L), any());

        mvc.perform(post("/api/projects/1/force-unlock").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.lock.not.held"));
    }

    @Test
    void cursorPriorityAcceptsRepeatedParametersAndReturnsTheSavedKey() throws Exception {
        mvc.perform(post("/api/projects/1/cursor-priority").with(csrf())
                        .param("cursorColumnPriority", "update_stamp", "update_time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.cursor.priority.saved"));

        verify(projectService).saveCursorPriority(eq(1L),
                eq(List.of("update_stamp", "update_time")), any());
    }

    @Test
    void aRefusedCursorPriorityIsReportedWithoutAnHtmlErrorPage() throws Exception {
        doThrow(new IllegalStateException("error.project.cursor.priority.cdc"))
                .when(projectService).saveCursorPriority(eq(1L), any(), any());

        mvc.perform(post("/api/projects/1/cursor-priority").with(csrf())
                        .param("cursorColumnPriority", "update_stamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("error.project.cursor.priority.cdc"));
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
        when(projectService.syncNow(eq(1L), any())).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/sync-now").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("msg.sync.stopped"))
                .andExpect(jsonPath("$.toastKind").value("warn"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.rowsInserted").value(1200))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    /**
     * A run deferred because another task holds the pool is neither a completion nor a
     * failure: the user pressed the button and nothing was read, so the toast must say it
     * will retry rather than claim 「同步已完成」.
     */
    @Test
    void aDeferredRunSaysItWillRetryInsteadOfClaimingCompletion() throws Exception {
        SyncResult result = new SyncResult();
        result.setDeferred(true);
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", result);
        when(projectService.syncNow(eq(1L), any())).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/sync-now").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("msg.sync.deferred"))
                .andExpect(jsonPath("$.toastKind").value("warn"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.summary").doesNotExist());
    }

    @Test
    void syncNowCarriesSkippedTablesForTheToastLoop() throws Exception {
        SyncResult result = new SyncResult();
        result.addSkipped("LOGS", "no usable cursor column and no primary key");
        // Outcome.executed is package-private by design; reflection keeps the boundary.
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", result);
        when(projectService.syncNow(eq(1L), any())).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/sync-now").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedTables[0].table").value("LOGS"))
                .andExpect(jsonPath("$.skippedTables[0].reason")
                        .value("no usable cursor column and no primary key"));
    }
}
