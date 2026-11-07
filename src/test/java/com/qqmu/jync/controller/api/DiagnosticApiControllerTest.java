package com.qqmu.jync.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.diagnostics.DiagnosticBundleService;
import com.qqmu.jync.service.diagnostics.DiagnosticBundleService.DiagnosticBundle;

/**
 * The bundle is a file download, not a JSON API: the body is the zip and the audit record is
 * written before the bytes leave, so a failed audit holds the file back.
 */
@WebMvcTest(DiagnosticApiController.class)
@WithMockUser(roles = "ADMIN")
class DiagnosticApiControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DiagnosticBundleService bundleService;
    @MockitoBean private AdminAuditService auditService;

    @Test
    void returnsTheZipAsAnAttachment() throws Exception {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04};
        when(bundleService.build()).thenReturn(new DiagnosticBundle(zip,
                "jync-diagnostics-20261103-101500.zip", 13));

        MvcResult result = mvc.perform(post("/api/diagnostics/bundle").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString(
                        "attachment; filename=\"jync-diagnostics-20261103-101500.zip\"")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(zip);
    }

    @Test
    void recordsTheExportInTheAuditTrail() throws Exception {
        when(bundleService.build()).thenReturn(new DiagnosticBundle(new byte[]{1},
                "jync-diagnostics-20261103-101500.zip", 13));

        mvc.perform(post("/api/diagnostics/bundle").with(csrf())).andExpect(status().isOk());

        verify(auditService).record(eq(AuditAction.DIAGNOSTICS_EXPORT),
                any(String.class));
    }

    @Test
    void mapsAnOversizedBundleToPayloadTooLarge() throws Exception {
        when(bundleService.build())
                .thenThrow(new DiagnosticBundleService.DiagnosticBundleTooLargeException());

        mvc.perform(post("/api/diagnostics/bundle").with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.diagnostics.tooLarge"));
    }

    @Test
    void doesNotReturnTheBundleWhenTheAuditWriteFails() throws Exception {
        when(bundleService.build()).thenReturn(new DiagnosticBundle(new byte[]{1},
                "jync-diagnostics-20261103-101500.zip", 13));
        doThrow(new IllegalStateException("error.audit.write"))
                .when(auditService).record(any(), any());

        mvc.perform(post("/api/diagnostics/bundle").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(false));
    }

    /*
     * Authorization is covered by SecurityConfigTest, which discovers every POST and
     * asserts a viewer gets 403. A @WebMvcTest slice does not load SecurityConfig's rules.
     */
}
