package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.service.sync.ContentAuditRunner;

/**
 * The JSON contract behind the detail page's on-demand content audit.
 *
 * <p>A SKIPPED or ERROR audit is still a successful request: the status describes the table,
 * not the HTTP call. Authorization is covered by SecurityConfigTest, which discovers the
 * POST and checks a viewer is refused.
 */
@WebMvcTest(ContentAuditApiController.class)
@WithMockUser(roles = "ADMIN")
class ContentAuditApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private ContentAuditRunner contentAuditRunner;

    @Test
    void controllerReturnsContentAuditJson() throws Exception {
        when(contentAuditRunner.auditProject(1L, "ORDERS")).thenReturn(new TableContentAudit(
                "ORDERS", TableContentAudit.Status.MISMATCH, 50, 47, 3, 1, 2,
                List.of("NAME", "UPDATED_AT"), null, 123L));

        mvc.perform(post("/api/projects/1/content-audit").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.contentAudit.table").value("ORDERS"))
                .andExpect(jsonPath("$.contentAudit.status").value("MISMATCH"))
                .andExpect(jsonPath("$.contentAudit.sampled").value(50))
                .andExpect(jsonPath("$.contentAudit.matched").value(47))
                .andExpect(jsonPath("$.contentAudit.mismatched").value(3))
                .andExpect(jsonPath("$.contentAudit.missingOnTarget").value(1))
                .andExpect(jsonPath("$.contentAudit.skippedColumns").value(2))
                .andExpect(jsonPath("$.contentAudit.mismatchColumns[0]").value("NAME"))
                .andExpect(jsonPath("$.contentAudit.mismatchColumns[1]").value("UPDATED_AT"))
                .andExpect(jsonPath("$.contentAudit.elapsedMs").value(123));
    }

    @Test
    void aSkippedAuditIsStillAnOkResponse() throws Exception {
        when(contentAuditRunner.auditProject(1L, "ORDERS")).thenReturn(
                TableContentAudit.skipped("ORDERS", "never-synced", 4L));

        mvc.perform(post("/api/projects/1/content-audit").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.contentAudit.status").value("SKIPPED"))
                .andExpect(jsonPath("$.contentAudit.detail").value("never-synced"));
    }

    @Test
    void anUnknownProjectIsABadRequest() throws Exception {
        when(contentAuditRunner.auditProject(99L, "ORDERS"))
                .thenThrow(new IllegalArgumentException("error.project.not.found"));

        mvc.perform(post("/api/projects/99/content-audit").param("table", "ORDERS").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.not.found"));
    }
}
