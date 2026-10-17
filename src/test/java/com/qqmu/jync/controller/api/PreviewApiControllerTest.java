package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.TablePreview;
import com.qqmu.jync.service.TablePreviewService;
import com.qqmu.jync.service.TablePreviewService.Side;

/**
 * The JSON contract behind the preview button.
 *
 * <p>app.js resolves {@code message} as a bundle key, so a refusal must reach it as a key
 * and a 4xx rather than an HTML error page. Authorization itself is covered by
 * SecurityConfigTest; this class signs in as ADMIN.
 */
@WebMvcTest(PreviewApiController.class)
@WithMockUser(roles = "ADMIN")
class PreviewApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private TablePreviewService tablePreviewService;

    private static TablePreview preview() {
        return new TablePreview("source", "Source DB", "PUBLIC", "ORDERS",
                List.of("ID", "NAME"), List.of(List.of("1", "order-1")), true, 12L);
    }

    @Test
    void returnsThePreviewPayloadAndDefaultsToTheSourceSide() throws Exception {
        when(tablePreviewService.preview(1L, "orders", Side.SOURCE)).thenReturn(preview());

        mvc.perform(get("/api/projects/1/preview").param("table", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.preview.side").value("source"))
                .andExpect(jsonPath("$.preview.connection").value("Source DB"))
                .andExpect(jsonPath("$.preview.columns[0]").value("ID"))
                .andExpect(jsonPath("$.preview.rows[0][1]").value("order-1"))
                .andExpect(jsonPath("$.preview.truncated").value(true))
                .andExpect(jsonPath("$.preview.elapsedMs").value(12));
    }

    @Test
    void passesTheTargetSideThrough() throws Exception {
        when(tablePreviewService.preview(1L, "orders", Side.TARGET)).thenReturn(preview());

        mvc.perform(get("/api/projects/1/preview")
                        .param("table", "orders")
                        .param("side", "target"))
                .andExpect(status().isOk());

        verify(tablePreviewService).preview(1L, "orders", Side.TARGET);
    }

    @Test
    void rejectsAnUnknownSideWith400AndAKey() throws Exception {
        mvc.perform(get("/api/projects/1/preview")
                        .param("table", "orders")
                        .param("side", "elsewhere"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.preview.side.invalid"));
    }

    @Test
    void surfacesAnUnknownTableAsA409WithTheRefusalKey() throws Exception {
        doThrow(new IllegalStateException("error.preview.table.unknown"))
                .when(tablePreviewService).preview(1L, "missing", Side.SOURCE);

        mvc.perform(get("/api/projects/1/preview").param("table", "missing"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.preview.table.unknown"));
    }
}
