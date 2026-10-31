package com.qqmu.jync.controller.api;

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

import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.connection.DriverLoader;
import com.qqmu.jync.service.metadata.ConnectionCatalogService;

/**
 * The table list behind the ETL form's target-table picker. It is advisory: a failure answers
 * 200 with {@code success=false} and a message key, because the form must stay usable and the
 * operator can type the table name by hand.
 */
@WebMvcTest(DatabaseApiController.class)
@WithMockUser(roles = "ADMIN")
class DatabaseTablesApiTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;
    @MockBean
    private DriverLoader driverLoader;
    @MockBean
    private DatabaseHealthService healthService;
    @MockBean
    private ConnectionCatalogService catalogService;

    @Test
    void listsTheTablesOfOneConnection() throws Exception {
        when(catalogService.listTables(7L)).thenReturn(
                new ConnectionCatalogService.Tables("appdb", List.of("orders", "users")));

        mvc.perform(get("/api/databases/7/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.schema").value("appdb"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.tables[0]").value("orders"))
                .andExpect(jsonPath("$.tables[1]").value("users"));
    }

    @Test
    void anUnreachableTargetStillAnswersWithAKeyInsteadOfAnHttpError() throws Exception {
        when(catalogService.listTables(7L)).thenThrow(
                new IllegalStateException("error.target.tables.read.failed"));

        mvc.perform(get("/api/databases/7/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.target.tables.read.failed"));
    }
}
