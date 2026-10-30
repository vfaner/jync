package com.qqmu.jync.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.ConnectionTestService;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.connection.DriverLoader;

/**
 * The form's "Test" button posts every field it shows, including the mining password. The
 * endpoint must forward both raw secrets: dropping the mining one made the readiness probe
 * report an Oracle CDB source as having no mining account configured even though one was
 * saved.
 */
@WebMvcTest(DatabaseApiController.class)
@WithMockUser(roles = "ADMIN")
class DatabaseTransientTestApiTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    @MockBean
    private DriverLoader driverLoader;

    /** The slice builds every constructor argument of the controller, health included. */
    @MockBean
    private DatabaseHealthService healthService;

    private void stubTransientTest() {
        ConnectionTestService.TestResult result = mock(ConnectionTestService.TestResult.class);
        when(service.testTransient(any(DatabaseConfig.class), any(), any())).thenReturn(result);
        when(service.previewUrl(any(DatabaseConfig.class))).thenReturn("jdbc:oracle:thin:@x");
    }

    @Test
    void theTransientTestForwardsTheMinerPasswordFromTheForm() throws Exception {
        stubTransientTest();

        mvc.perform(post("/api/databases/test").with(csrf())
                        .param("type", "ORACLE")
                        .param("role", "SOURCE")
                        .param("host", "db.example")
                        .param("port", "1521")
                        .param("minerUsername", "C##MINER")
                        .param("rawPassword", "pw")
                        .param("rawMinerPassword", "miner-pw"))
                .andExpect(status().isOk());

        verify(service).testTransient(any(DatabaseConfig.class), eq("pw"), eq("miner-pw"));
    }

    @Test
    void aBlankMinerPasswordIsForwardedSoTheStoredOneCanBeKept() throws Exception {
        stubTransientTest();

        mvc.perform(post("/api/databases/test").with(csrf())
                        .param("type", "ORACLE")
                        .param("role", "SOURCE")
                        .param("host", "db.example")
                        .param("minerUsername", "C##MINER")
                        .param("rawPassword", "")
                        .param("rawMinerPassword", ""))
                .andExpect(status().isOk());

        verify(service).testTransient(any(DatabaseConfig.class), eq(""), eq(""));
    }
}
