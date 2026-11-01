package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.ChartSeries;
import com.qqmu.jync.service.metrics.DbMetricsView;
import com.qqmu.jync.service.metrics.MetricsQueryService;

/**
 * The database performance page.
 *
 * <p>Runs against the real filter chain rather than a sliced context: the page is admin-only
 * and that rule lives in {@code SecurityConfig}, so a slice would prove nothing about who may
 * open it. The two services are mocked so the page renders from a known sample instead of
 * whatever the metadata store happens to hold.
 *
 * <p>Three states matter to a reader: a healthy database, one whose newest probe failed (the
 * page still opens — the history is the useful part — with a red banner), and a product whose
 * runtime counters the reader cannot provide (placeholder, never made-up zeros).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dbperf;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class DatabasePerformanceViewTest {

    private static final Long CONNECTION = 1L;

    @Autowired private MockMvc mvc;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private MetricsQueryService metricsQueryService;

    @Test
    void aViewerIsRefusedThePage() throws Exception {
        mvc.perform(get("/databases/{id}/performance", CONNECTION).with(as(UserRole.VIEWER)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));
    }

    @Test
    void aViewerIsRefusedTheJson() throws Exception {
        mvc.perform(get("/databases/{id}/metrics", CONNECTION).with(as(UserRole.VIEWER)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void anUnhealthyConnectionStillRendersWithTheRedBanner() throws Exception {
        givenConnection();
        givenSample(false, "连接被拒绝", null, null, null, null);

        String html = page();

        assertThat(html).contains("连接被拒绝");
        assertThat(bannerTag(html)).doesNotContain("hidden");
        // The poller localises these two keys itself when a probe fails after the page
        // opened, so the translations have to travel with the banner — resolved, not the
        // "??key_locale??" Thymeleaf emits for a missing message.
        assertThat(bannerTag(html)).contains("data-timeout=").doesNotContain("??");
    }

    @Test
    void aHealthyConnectionKeepsTheBannerHiddenInsteadOfRemovingIt() throws Exception {
        givenConnection();
        givenSample(true, null, 12, 30.0, 99.5, 0);

        String html = page();

        // Removed from the DOM, the banner could never appear when the database goes down
        // while the page is open — the poller only toggles `hidden`.
        assertThat(bannerTag(html)).contains("hidden");
        assertThat(html).doesNotContain("id=\"runtime-unsupported\"");
    }

    @Test
    void aProductWithoutRuntimeSupportShowsThePlaceholder() throws Exception {
        givenConnection();
        // HANA reports sessions and statements only; cache hit and locks are unknown, and a
        // product with no reader at all reports all four as unknown.
        givenSample(true, null, null, null, null, null);

        assertThat(page()).contains("id=\"runtime-unsupported\"");
    }

    @Test
    void thePageTellsTheScriptWhereToPoll() throws Exception {
        givenConnection();
        givenSample(true, null, 12, 30.0, 99.5, 0);

        String html = page();

        assertThat(html).contains("data-metrics-url=\"/databases/1/metrics\"");
        assertThat(html).contains("data-mode=\"database\"");
    }

    /**
     * 占用空间来自目录统计的原始字节数（MySQL 是 DATA_LENGTH + INDEX_LENGTH）。直接显示
     * "16384" 说不清单位，脚本需要知道这张卡片和这条曲线按字节换算成 KB / MB / GB。
     */
    @Test
    void theSpaceCardAndChartAskTheScriptForByteUnits() throws Exception {
        givenConnection();
        givenSample(true, null, 12, 30.0, 99.5, 0);

        String html = page();

        assertThat(html).containsPattern("data-current=\"bytes\"[^>]*data-format=\"bytes\"");
        assertThat(html).containsPattern("data-chart=\"bytes\"[^>]*data-bytes=\"true\"");
    }

    /**
     * 连接延迟同样以毫秒采样：慢连接一次探测要好几秒，直接画 "2345 ms" 不如按时间单位
     * 换算（和项目页的同步延迟、变更日志的耗时同一种读法）。
     */
    @Test
    void theLatencyCardAndChartAskTheScriptForDurationUnits() throws Exception {
        givenConnection();
        givenSample(true, null, 12, 30.0, 99.5, 0);

        String html = page();

        assertThat(html).containsPattern("data-current=\"latency\"[^>]*data-format=\"duration\"");
        assertThat(html).containsPattern("data-chart=\"latency\"[^>]*data-duration=\"true\"");
    }

    private String page() throws Exception {
        return mvc.perform(get("/databases/{id}/performance", CONNECTION).with(as(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The banner's own tag, from its id up to the closing '&gt;' — attributes included. */
    private static String bannerTag(String html) {
        int start = html.indexOf("id=\"db-down-banner\"");
        assertThat(start).as("banner rendered").isGreaterThanOrEqualTo(0);
        return html.substring(start, html.indexOf('>', start));
    }

    private void givenConnection() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(CONNECTION);
        config.setName("orders-prod");
        config.setType(DatabaseType.MYSQL);
        config.setRole(ConnectionRole.SOURCE);
        when(databaseConfigService.findById(CONNECTION)).thenReturn(Optional.of(config));
    }

    private void givenSample(boolean ok, String message, Integer sessions, Double qps,
                             Double cacheHit, Integer locks) {
        Map<String, Double> current = new LinkedHashMap<>();
        current.put("latency", ok ? 12.0 : null);
        current.put("sessions", sessions == null ? null : sessions.doubleValue());
        current.put("qps", qps);
        current.put("cacheHit", cacheHit);
        current.put("locks", locks == null ? null : locks.doubleValue());
        current.put("tableCount", 12.0);
        current.put("rowCount", 900.0);
        current.put("bytes", 1048576.0);
        DbMetricsView view = new DbMetricsView(ok, message,
                Instant.parse("2026-11-01T12:00:00Z"), ok ? 100.0 : 0.0,
                List.<ChartSeries>of(), current);
        when(metricsQueryService.database(eq(CONNECTION), any())).thenReturn(view);
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
