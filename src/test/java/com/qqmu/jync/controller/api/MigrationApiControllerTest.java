package com.qqmu.jync.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.connection.MigrationConnectionTestResult;
import com.qqmu.jync.service.migration.connection.MigrationDiscoveryItem;

@WebMvcTest(MigrationApiController.class)
@WithMockUser(roles = "ADMIN")
class MigrationApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private MigrationConnectionService service;

    @Test
    void discoveryReturnsTheParsedIndexList() throws Exception {
        when(service.listIndices(3L, "orders-*")).thenReturn(List.of(
                new MigrationDiscoveryItem(
                        "orders-2026", 12L, "1kb", "open", "green")));

        mvc.perform(get("/api/migrations/indices")
                        .param("connectionId", "3").param("pattern", "orders-*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.indices[0].name").value("orders-2026"))
                .andExpect(jsonPath("$.indices[0].docsCount").value(12));
    }

    @Test
    void discoveryFailuresAnswerWithAKeyInsteadOfAnHttpError() throws Exception {
        when(service.listIndices(3L, "")).thenThrow(
                new IllegalStateException("error.migration.connection.unreachable"));

        mvc.perform(get("/api/migrations/indices").param("connectionId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.migration.connection.unreachable"));
    }

    @Test
    void aTransientConnectionCanBeTestedBeforeSaving() throws Exception {
        when(service.testTransient(any(), any(), any())).thenReturn(
                new MigrationConnectionTestResult(true, null,
                        "elasticsearch", "7.17.22", "prod", 12));

        mvc.perform(post("/api/migrations/connections/test").with(csrf())
                        .param("kind", "ELASTICSEARCH")
                        .param("baseUrl", "http://127.0.0.1:9200")
                        .param("authType", "NONE")
                        .param("rawPassword", "")
                        .param("rawApiKey", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.version").value("7.17.22"));
    }

    @Test
    void aFailedTestStillAnswers200WithTheReason() throws Exception {
        when(service.testTransient(any(), any(), any())).thenReturn(
                new MigrationConnectionTestResult(false,
                        "GET / -> 401 Unauthorized", null, null, null, 5));

        mvc.perform(post("/api/migrations/connections/test").with(csrf())
                        .param("kind", "EASYSEARCH")
                        .param("baseUrl", "http://127.0.0.1:9200")
                        .param("authType", "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("GET / -> 401 Unauthorized"));
    }

    @Test
    void aSavedConnectionCanBeTested() throws Exception {
        when(service.test(7L)).thenReturn(new MigrationConnectionTestResult(
                true, null, "easysearch", "2.0.0", "easy", 8));

        mvc.perform(post("/api/migrations/connections/7/test").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.product").value("easysearch"));
    }
}
