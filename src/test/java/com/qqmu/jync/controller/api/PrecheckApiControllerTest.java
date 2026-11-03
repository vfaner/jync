package com.qqmu.jync.controller.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.dto.PrecheckReport;
import com.qqmu.jync.service.sync.SyncPrecheckService;

/**
 * The JSON contract behind the detail page's pre-sync check.
 *
 * <p>The report is data, not a request failure: a project whose source is down still answers
 * 200 with an ERROR check. Authorization is covered by SecurityConfigTest.
 */
@WebMvcTest(PrecheckApiController.class)
@WithMockUser(roles = "ADMIN")
class PrecheckApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private SyncPrecheckService precheckService;

    @Test
    void returnsTheReportWithEveryCheckField() throws Exception {
        PrecheckReport report = new PrecheckReport(1L, "orders-sync", Instant.parse(
                "2026-11-03T10:15:30Z"), 42L, List.of(
                new PrecheckReport.Check("connection.source", PrecheckReport.Severity.OK,
                        "CONNECTION", "source", "precheck.connection.ok", null),
                new PrecheckReport.Check("target.table.missing", PrecheckReport.Severity.ERROR,
                        "TABLE", "ORDERS", "precheck.target.tableMissing", "ORDERS")));
        when(precheckService.precheck(1L)).thenReturn(report);

        mvc.perform(get("/api/projects/1/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.precheck.projectId").value(1))
                .andExpect(jsonPath("$.precheck.projectName").value("orders-sync"))
                .andExpect(jsonPath("$.precheck.checkedAt").exists())
                .andExpect(jsonPath("$.precheck.elapsedMs").value(42))
                .andExpect(jsonPath("$.precheck.passed").value(false))
                .andExpect(jsonPath("$.precheck.checks.length()").value(2))
                .andExpect(jsonPath("$.precheck.checks[0].code").value("connection.source"))
                .andExpect(jsonPath("$.precheck.checks[0].severity").value("OK"))
                .andExpect(jsonPath("$.precheck.checks[1].severity").value("ERROR"))
                .andExpect(jsonPath("$.precheck.checks[1].objectName").value("ORDERS"))
                .andExpect(jsonPath("$.precheck.checks[1].messageKey")
                        .value("precheck.target.tableMissing"))
                .andExpect(jsonPath("$.precheck.checks[1].detail").value("ORDERS"));
    }

    @Test
    void anUnknownProjectIsABadRequest() throws Exception {
        when(precheckService.precheck(99L))
                .thenThrow(new IllegalArgumentException("error.project.not.found"));

        mvc.perform(get("/api/projects/99/precheck"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.not.found"));
    }
}
