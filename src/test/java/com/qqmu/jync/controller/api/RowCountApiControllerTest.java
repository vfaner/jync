package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.TableRowCount;
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
    @MockBean private TablePreviewService tablePreviewService;

    @Test
    void returnsBothSideCounts() throws Exception {
        when(tablePreviewService.rowCount(1L, "orders")).thenReturn(new TableRowCount(
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
}
