package com.qqmu.jync.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.controller.api.DatabaseApiController;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.connection.DriverLoader;

/**
 * The connections page asks for health only for the rows it is showing, so the endpoint takes
 * ids rather than sweeping every configured database — a page with ten rows must not dial two
 * hundred hosts.
 */
@WebMvcTest(DatabaseApiController.class)
@WithMockUser(roles = "ADMIN")
class DatabaseHealthApiTest {

    @Autowired private MockMvc mvc;
    @MockBean private DatabaseConfigService service;
    @MockBean private DatabaseHealthService healthService;
    @MockBean private DriverLoader driverLoader;

    private DatabaseConfig config(long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setName("db-" + id);
        config.setRole(ConnectionRole.SOURCE);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    @Test
    void theEndpointAnswersOneVerdictPerListedId() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(config(1L)));
        when(service.findById(2L)).thenReturn(Optional.of(config(2L)));
        when(healthService.check(anyList(), anyBoolean())).thenReturn(List.of(
                new DatabaseHealthService.Health(1L, true, 12, null),
                new DatabaseHealthService.Health(2L, false, 3_000, "[SQLState 08S01] refused")));

        mvc.perform(get("/api/databases/health").param("ids", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].ok").value(true))
                .andExpect(jsonPath("$[0].elapsedMs").value(12))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].ok").value(false))
                .andExpect(jsonPath("$[1].message").value("[SQLState 08S01] refused"));
    }

    @Test
    void onlyTheIdsThatStillExistAreChecked() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(config(1L)));
        when(service.findById(9L)).thenReturn(Optional.empty());
        when(healthService.check(anyList(), anyBoolean())).thenReturn(List.of());

        mvc.perform(get("/api/databases/health").param("ids", "1,9")).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DatabaseConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(healthService).check(captor.capture(), anyBoolean());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .extracting(DatabaseConfig::getId).containsExactly(1L);
    }

    @Test
    void refreshForcesAFreshCheck() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(config(1L)));
        when(healthService.check(anyList(), anyBoolean())).thenReturn(List.of());

        mvc.perform(get("/api/databases/health").param("ids", "1").param("refresh", "true"))
                .andExpect(status().isOk());

        verify(healthService).check(anyList(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void noIdsMeansNoDatabaseIsTouched() throws Exception {
        mvc.perform(get("/api/databases/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(healthService, never()).check(any(), anyBoolean());
    }

    @Test
    void nonsenseAndDuplicateIdsAreIgnored() throws Exception {
        when(service.findById(2L)).thenReturn(Optional.of(config(2L)));
        when(healthService.check(anyList(), anyBoolean())).thenReturn(List.of());

        mvc.perform(get("/api/databases/health").param("ids", "2, x, ,2"))
                .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(1)).findById(2L);
        verify(service, never()).findById(1L);
    }

    @Test
    void theIdListIsCappedSoOneRequestCannotSweepTheWholeInstallation() throws Exception {
        List<String> ids = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            ids.add(String.valueOf(id));
            when(service.findById(id)).thenReturn(Optional.of(config(id)));
        }
        when(healthService.check(anyList(), anyBoolean())).thenReturn(List.of());

        mvc.perform(get("/api/databases/health").param("ids", String.join(",", ids)))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DatabaseConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(healthService).check(captor.capture(), anyBoolean());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).hasSize(40);
    }
}
