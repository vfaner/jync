package com.qqmu.jync.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.hasItem;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.AppMetricsView;
import com.qqmu.jync.service.metrics.ChartSeries;
import com.qqmu.jync.service.metrics.MetricsQueryService;
import com.qqmu.jync.service.metrics.MetricsRange;

/**
 * The shared process resources moved from a project's page to the dashboard, because CPU,
 * heap and GC describe the JVM every task on this server runs in — not one task. The gate
 * moves with them: a viewer may open the dashboard but must never receive these figures.
 *
 * <p>Runs against the real filter chain: the admin-only rule lives in {@code SecurityConfig},
 * and a sliced context would not load it, so a refusal asserted there would prove nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dashproc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class DashboardProcessMetricsTest {

    @Autowired private MockMvc mvc;
    @MockBean private MetricsQueryService metricsQueryService;

    @Test
    void anAdminGetsTheProcessCurves() throws Exception {
        givenView();

        mvc.perform(get("/dashboard/process-metrics?range=1h").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[*].key",
                        hasItem("cpu")))
                .andExpect(jsonPath("$.current.cpu").value(12.5))
                .andExpect(jsonPath("$.lastSample").value("2026-11-01T12:00:00Z"));
    }

    @Test
    void aViewerIsRefusedTheProcessCurves() throws Exception {
        mvc.perform(get("/dashboard/process-metrics").with(as(UserRole.VIEWER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));
    }

    @Test
    void theRangeParameterReachesTheQuery() throws Exception {
        givenView();

        mvc.perform(get("/dashboard/process-metrics?range=7d").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(metricsQueryService).app(eq(MetricsRange.LAST_WEEK));
    }

    private void givenView() {
        Map<String, Double> current = new LinkedHashMap<>();
        current.put("cpu", 12.5);
        when(metricsQueryService.app(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AppMetricsView(Instant.parse("2026-11-01T12:00:00Z"),
                        List.of(new ChartSeries("cpu", "CPU", List.of())), current));
    }

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
