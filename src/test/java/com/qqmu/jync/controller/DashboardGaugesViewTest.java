package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The 看板 opens with four donuts for the host the sync runs on: load, CPU, memory, disk.
 *
 * <p>They sit directly under the stat tiles as a bare row — no card around them — and refresh
 * themselves every five seconds from a fragment endpoint that samples the counters and renders
 * nothing else. The values are a point-in-time reading, so every test stubs the sampler.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DashboardGaugesViewTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static final SystemMetrics.Snapshot SAMPLE = new SystemMetrics.Snapshot(
            50.0, 8, 63.0, 14 * GIB, 16 * GIB, 95 * GIB, 100 * GIB);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DashboardService dashboardService;
    @MockBean
    private SystemMetrics systemMetrics;

    private String render(SystemMetrics.Snapshot snapshot, Locale locale) throws Exception {
        return render(snapshot, locale, false);
    }

    private String render(SystemMetrics.Snapshot snapshot, Locale locale, boolean defaultPassword)
            throws Exception {
        when(dashboardService.collect()).thenReturn(new DashboardService.Stats());
        when(systemMetrics.snapshot()).thenReturn(snapshot);

        return mvc.perform(get("/dashboard").locale(locale).with(as(defaultPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theDashboardShowsTheFourGauges() throws Exception {
        String html = render(SAMPLE, Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("负载").contains("CPU").contains("内存").contains("磁盘")
                .contains("50%").contains("63%").contains("88%").contains("95%")
                .contains("14.0 GB / 16.0 GB")
                .contains("8 核心");
    }

    @Test
    void englishRendersTheEnglishWording() throws Exception {
        String html = render(SAMPLE, Locale.ENGLISH);

        assertThat(html).contains("Load").contains("cores").contains("Memory").contains("Disk")
                .doesNotContain("负载").doesNotContain("内存");
    }

    @Test
    void aJvmWithoutTheCountersStillRendersTheGauges() throws Exception {
        String html = render(SystemMetrics.Snapshot.UNKNOWN, Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("id=\"resource-gauges\"").contains("—");
    }

    @Test
    void theGaugesPollTheirOwnFragmentEndpoint() throws Exception {
        String html = render(SAMPLE, Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("id=\"resource-gauges\"")
                .contains("data-refresh-url=\"/dashboard/resource-gauges\"");
    }

    /** Sits with the stat tiles, above the project table and the change log. */
    @Test
    void theGaugesSitAboveTheProjectOverview() throws Exception {
        String html = render(SAMPLE, Locale.SIMPLIFIED_CHINESE);

        assertThat(html.indexOf("resource-gauges")).isGreaterThanOrEqualTo(0)
                .isLessThan(html.indexOf("项目概览"));
    }

    /** The 5s poll replaces only this row: bare gauges, no card wrapper, no dashboard. */
    @Test
    void theFragmentEndpointRendersJustTheGauges() throws Exception {
        when(systemMetrics.snapshot()).thenReturn(SAMPLE);

        String fragment = mvc.perform(get("/dashboard/resource-gauges")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(as(false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("负载").contains("id=\"resource-gauges\"")
                .contains("class=\"gauges\"")
                .doesNotContain("class=\"card\"")
                .doesNotContain("stat-grid");
    }

    /** A seeded-password account sees an empty page, so nothing may start polling. */
    @Test
    void theDefaultPasswordAccountGetsNoGauges() throws Exception {
        String html = render(SAMPLE, Locale.SIMPLIFIED_CHINESE, true);

        assertThat(html).doesNotContain("resource-gauges");
    }

    private static RequestPostProcessor as(boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
