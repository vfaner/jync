package com.qqmu.jync.controller.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.TableMaintenanceService;
import com.qqmu.jync.service.TableReloadJobService;
import com.qqmu.jync.service.task.SyncTaskRunner;

@WebMvcTest(TableMaintenanceApiController.class)
@WithMockUser(roles = "ADMIN")
class TableMaintenanceApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private TableMaintenanceService tableMaintenanceService;
    @MockitoBean private TableReloadJobService tableReloadJobService;
    @MockitoBean private ProjectService projectService;

    @Test
    void reloadReportsSuccessAndRowCounts() throws Exception {
        SyncResult result = new SyncResult();
        result.setRowsInserted(7);
        result.setRowsUpdated(1);
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "executed", result);
        when(tableMaintenanceService.reload(1L, "ORDERS")).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/table-reload").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.table.reload.done"))
                .andExpect(jsonPath("$.rowsInserted").value(7))
                .andExpect(jsonPath("$.rowsUpdated").value(1));
    }

    @Test
    void aRefusalIsReturnedAsALocalizedMessageKey() throws Exception {
        when(tableMaintenanceService.reload(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("error.table.reload.stopFirst"));

        mvc.perform(post("/api/projects/1/table-reload").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.table.reload.stopFirst"));
    }

    @Test
    void startsAndPollsABackgroundReloadJob() throws Exception {
        when(tableReloadJobService.submit(1L, "ORDERS")).thenReturn("job-1");
        when(tableReloadJobService.find(1L, "job-1")).thenReturn(Optional.of(
                new TableReloadJobService.JobView("job-1", 1L, "ORDERS",
                        TableReloadJobService.Status.RUNNING, null, null, List.of(),
                        null, null, null)));

        mvc.perform(post("/api/projects/1/table-reload-job").param("table", "ORDERS")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.jobId").value("job-1"));

        mvc.perform(get("/api/projects/1/table-reload-job/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("RUNNING"))
                .andExpect(jsonPath("$.job.table").value("ORDERS"));
    }

    @Test
    void cancellingAJobReturnsSuccessEvenThoughTheWorkerMayStillBeStopping() throws Exception {
        when(tableReloadJobService.cancel(1L, "job-1")).thenReturn(true);

        mvc.perform(post("/api/projects/1/table-reload-job/job-1/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("msg.table.reload.cancelling"));
    }

    @Test
    void aBusyLockIsReportedWithoutQueuing() throws Exception {
        SyncTaskRunner.Outcome outcome = ReflectionTestUtils.invokeMethod(
                SyncTaskRunner.Outcome.class, "busy");
        when(tableMaintenanceService.reload(1L, "ORDERS")).thenReturn(outcome);

        mvc.perform(post("/api/projects/1/table-reload").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.table.reload.busy"));
    }

}
