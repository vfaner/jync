package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.TableRowCount;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.RowCountJobService;
import com.qqmu.jync.service.TablePreviewService;

/**
 * The JSON contract behind the on-demand row-count check.
 *
 * <p>Authorization itself is covered by SecurityConfigTest; this class signs in as ADMIN
 * and checks that both sides survive serialization unchanged.
 */
@WebMvcTest(RowCountApiController.class)
@WithMockUser(roles = "ADMIN")
class RowCountApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private TablePreviewService tablePreviewService;
    @MockitoBean private RowCountJobService rowCountJobService;
    @MockitoBean private ProjectService projectService;

    @Test
    void returnsBothSideCounts() throws Exception {
        when(tablePreviewService.rowCount(1L, "orders", true)).thenReturn(new TableRowCount(
                "orders",
                new TableRowCount.SideCount(TableRowCount.Status.OK, 3L, 5L),
                new TableRowCount.SideCount(TableRowCount.Status.OK, 1L, 6L),
                11L));

        mvc.perform(get("/api/projects/1/row-count").param("table", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rowCount.source.status").value("OK"))
                .andExpect(jsonPath("$.rowCount.source.count").value(3))
                .andExpect(jsonPath("$.rowCount.target.count").value(1))
                .andExpect(jsonPath("$.rowCount.elapsedMs").value(11));
    }

    @Test
    void startsAndReadsTheExactCountJob() throws Exception {
        when(rowCountJobService.submit(1L, "orders")).thenReturn("job-1");
        when(rowCountJobService.find(1L, "job-1")).thenReturn(Optional.of(
                new RowCountJobService.JobView("job-1", 1L, "orders",
                        RowCountJobService.Status.DONE,
                        new TableRowCount("orders",
                                new TableRowCount.SideCount(TableRowCount.Status.OK, 3L, 5L),
                                new TableRowCount.SideCount(TableRowCount.Status.OK, 1L, 6L),
                                11L), null)));

        mvc.perform(post("/api/projects/1/row-count-job").with(csrf())
                        .param("table", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"));

        mvc.perform(get("/api/projects/1/row-count-job/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("DONE"))
                .andExpect(jsonPath("$.job.rowCount.source.count").value(3))
                .andExpect(jsonPath("$.job.rowCount.target.count").value(1));
    }

    @Test
    void cancelsTheExactCountJob() throws Exception {
        when(rowCountJobService.cancel(1L, "job-1")).thenReturn(true);

        mvc.perform(post("/api/projects/1/row-count-job/job-1/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void forwardsEstimateModeToTheService() throws Exception {
        when(tablePreviewService.rowCount(1L, "orders", false)).thenReturn(new TableRowCount(
                "orders",
                new TableRowCount.SideCount(TableRowCount.Status.OK, 3_000_000L, 2L, true),
                new TableRowCount.SideCount(TableRowCount.Status.OK, 2_999_000L, 2L, true),
                4L));

        mvc.perform(get("/api/projects/1/row-count")
                        .param("table", "orders")
                        .param("mode", "estimate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount.source.approximate").value(true))
                .andExpect(jsonPath("$.rowCount.target.approximate").value(true));
    }
}
