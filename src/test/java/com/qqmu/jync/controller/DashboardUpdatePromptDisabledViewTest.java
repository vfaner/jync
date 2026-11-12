package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.metrics.MetricsQueryService;

/**
 * An air-gapped host with the update check switched off must see no version prompt at all —
 * the same silence it had before the dashboard dialog existed.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class, SystemMetrics.class})
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.update-check.enabled=false"
})
class DashboardUpdatePromptDisabledViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private BuildProperties buildProperties;
    @MockitoBean
    private MetricsQueryService metricsQueryService;

    @Test
    void noVersionPromptOrCheckIsRendered() throws Exception {
        when(dashboardService.collect()).thenReturn(new DashboardService.Stats());

        String page = mvc.perform(get("/dashboard").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("update-available-modal");
        assertThat(page).doesNotContain("/api/version/check");
        assertThat(page).doesNotContain("/api/version/ignore");
    }
}
