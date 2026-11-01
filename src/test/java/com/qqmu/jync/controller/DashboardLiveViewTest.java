package com.qqmu.jync.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.DashboardService;
import com.qqmu.jync.service.SystemMetrics;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.metrics.MetricsQueryService;

/**
 * The dashboard is a live screen: the title block is gone, the tiles, the trend chart and the
 * activity feed each carry their own refresh address, and every one of those endpoints renders
 * only its own fragment so a poll never re-runs the page.
 */
@WebMvcTest(DashboardController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DashboardLiveViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DashboardService dashboardService;
    @MockBean
    private SystemMetrics systemMetrics;
    @MockBean
    private MetricsQueryService metricsQueryService;

    private String dashboard(Locale locale) throws Exception {
        return dashboard(locale, UserRole.ADMIN);
    }

    private String dashboard(Locale locale, UserRole role) throws Exception {
        when(dashboardService.collect()).thenReturn(stats());
        when(systemMetrics.snapshot()).thenReturn(SystemMetrics.Snapshot.UNKNOWN);
        return mvc.perform(get("/dashboard").locale(locale).with(as(role, false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theDashboardNoLongerOpensWithATitleBlock() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).doesNotContain("<h1");
        // The toolbar keeps the actions and gains the live badge.
        assertThat(html).contains("class=\"live-pill\"").contains("新建同步任务");
    }

    @Test
    void theStatisticsPollTheirOwnFragmentEndpoint() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("id=\"stat-grid\"")
                .contains("data-refresh-url=\"/dashboard/live-stats\"");
    }

    @Test
    void theLiveFeedPollsItsOwnFragmentEndpoint() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("id=\"activity-feed\"")
                .contains("data-refresh-url=\"/dashboard/activity-feed\"");
    }

    /**
     * 趋势图是一张图两条线：实时同步与 ETL 各一条。切换的是「变更数 / 行数」，不是
     * 「实时同步 / ETL」——后者现在同时画出来，用颜色区分。
     */
    @Test
    void theChartDrawsBothSeriesAndSwitchesBetweenChangesAndRows() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("id=\"activity-chart\"")
                .contains("data-series-url=\"/dashboard/activity-series\"")
                .contains("data-sync-label=")
                .contains("data-etl-label=")
                .contains("data-metric=\"changes\"")
                .contains("data-metric=\"rows\"");
        // 图例：两条线的名字与颜色都要在页面上，读者才知道哪根是哪个。
        assertThat(html).contains("chart-legend")
                .contains("实时同步")
                .contains("ETL 任务")
                .doesNotContain("data-series=\"sync\"");
    }

    /**
     * 进程资源（CPU / 堆 / 线程 / GC）是整个 JVM 的，不属于任何一张同步表，所以它现在只有
     * 看板这一块；采样历史换成由 metrics.js 拉取，不再用进程内的环形缓冲。
     */
    /**
     * 进程资源说的是这台机器上的 JVM，不是某个任务，所以它不套卡片：直接排在资源环和
     * 同步趋势之间，与上面的整机指标连成一段。
     */
    @Test
    void theProcessRowSitsAboveTheTrendChartAndCarriesNoCard() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);

        int gauges = html.indexOf("id=\"resource-gauges\"");
        int process = html.indexOf("id=\"process-block\"");
        int chart = html.indexOf("id=\"activity-chart\"");
        assertThat(gauges).isGreaterThanOrEqualTo(0);
        assertThat(process).isGreaterThan(gauges);
        assertThat(chart).isGreaterThan(process);
        assertThat(html).contains("class=\"process-row\"");
    }

    @Test
    void theProcessCardKnowsWhereToFetchItsSamples() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE, UserRole.ADMIN);

        assertThat(html).contains("id=\"process-block\"")
                .contains("data-metrics-url=\"/dashboard/process-metrics\"")
                .contains("data-range=\"7d\"")
                .contains("/js/metrics.js");
        // 四张数字卡：CPU / 堆 / 线程 / GC 次数。
        for (String key : new String[] {"cpu", "heap", "threads", "gcCount"}) {
            assertThat(html).as(key).contains("data-current=\"" + key + "\"");
        }
        // 四条曲线，其中 GC 耗时是这次新加的一条。
        for (String key : new String[] {"cpu", "heap", "threads", "gcTime"}) {
            assertThat(html).as(key).contains("data-chart=\"" + key + "\"");
        }
        // 旧的进程内内存趋势整块退场，不再有第二个内存图。
        assertThat(html).doesNotContain("id=\"memory-chart\"")
                .doesNotContain("/dashboard/memory-series");
    }

    /** 面向本机所有账号的 JVM 数字，只读账号不该在页面上看到，也就不该去轮询它。 */
    @Test
    void aViewerGetsNoProcessCard() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE, UserRole.VIEWER);

        assertThat(html).doesNotContain("id=\"process-block\"")
                .doesNotContain("/dashboard/process-metrics");
    }

    @Test
    void theChartSitsLeftOfTheLiveFeedAndAboveTheProjectTable() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        int chart = html.indexOf("id=\"activity-chart\"");
        int feed = html.indexOf("id=\"activity-feed\"");
        int table = html.indexOf("id=\"project-overview\"");
        assertThat(chart).isGreaterThanOrEqualTo(0);
        assertThat(feed).isGreaterThan(chart);
        assertThat(table).isGreaterThan(chart);
    }

    /**
     * 每一块都在自己轮询之后，顶上那个刷新整页的按钮就没有存在意义了：它只是把本来就
     * 自动更新的内容再画一遍。主操作「新建同步任务」留下。
     */
    @Test
    void theToolbarNoLongerOffersAManualRefresh() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        // 只看顶部操作区：页脚那条反馈链接同样是 ghost 小按钮，与这次的改动无关
        int at = html.indexOf("class=\"page-actions\"");
        String actions = html.substring(at, html.indexOf("</div>", at));
        assertThat(actions).contains("新建同步任务")
                .doesNotContain("arrow-clockwise")
                .doesNotContain("href=\"/dashboard\"");
    }

    /**
     * 10 秒的轮询一次换两块：四块统计卡和任务概览表。概览原本只能靠顶部那个刷新按钮
     * 更新，按钮删掉后它必须跟着同一个响应走——一次 collect() 同时喂两块。
     */
    @Test
    void theLiveStatsFragmentCarriesTheTilesAndTheProjectOverview() throws Exception {
        when(dashboardService.collect()).thenReturn(stats());

        String fragment = mvc.perform(get("/dashboard/live-stats")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(as(false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("id=\"stat-grid\"").contains("ETL 任务")
                .contains("张同步表")
                .contains("id=\"project-overview\"")
                .doesNotContain("id=\"activity-chart\"")
                .doesNotContain("id=\"activity-feed\"");
    }

    /**
     * liveStats 片段定义在 content 之外，就是为了整页渲染时不多出第二套统计卡和概览表；
     * 这两块在同一页上只能出现一次。
     */
    @Test
    void theComposedFragmentDoesNotRenderTwiceOnThePage() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(countOccurrences(html, "id=\"stat-grid\"")).isEqualTo(1);
        assertThat(countOccurrences(html, "id=\"project-overview\"")).isEqualTo(1);
    }

    /**
     * 八个数字格子（四块大数 + 正在运行 + 已启用 ETL + 同步表 + 今日行数）都标了
     * data-count，app.js 才知道该让哪些数字滚动。少标一个，那个格子就永远是硬切过去的。
     */
    @Test
    void everyCounterIsMarkedForTheCountUp() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(countOccurrences(html, "data-count")).isEqualTo(8);
    }

    /**
     * 四块统计卡要覆盖两种任务族：第一块只管实时同步，第二块单独说 ETL——原先第一块写
     * 「同步任务总数」，ETL 任务在那个数里根本不存在。数据库连接块顺带给出同步表数，免得
     * 为了多放一块 ETL 卡把四环对不齐。
     */
    @Test
    void theTilesSplitRealTimeFromEtlAndKeepTheTableCountOnTheConnectionsTile() throws Exception {
        String html = dashboard(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("实时同步任务", "ETL 任务", "个已启用")
                .contains("数据库连接", "张同步表");
        // The ETL tile's big number is the task total, its sub-line the enabled share.
        assertThat(html).containsPattern(">5</div>\\s*<div class=\"stat-l\">ETL 任务</div>");
        assertThat(html).containsPattern(">2</span>\\s*<span>个已启用</span>");
        // The connections tile keeps the connection total, with the table count underneath.
        assertThat(html).containsPattern(">4</div>\\s*<div class=\"stat-l\">数据库连接</div>");
        assertThat(html).containsPattern(">128</span>\\s*<span>张同步表</span>");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** The feed poll swaps only the rows, and the empty state travels with them. */
    @Test
    void theFeedFragmentRendersOnlyTheItems() throws Exception {
        when(dashboardService.recentChanges()).thenReturn(stats().getRecentChanges());

        String fragment = mvc.perform(get("/dashboard/activity-feed")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(as(false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("customers").contains("插入")
                .doesNotContain("id=\"activity-feed\"")
                .doesNotContain("card-head");
    }

    @Test
    void theFeedFragmentCarriesTheEmptyStateWhenNothingHappened() throws Exception {
        when(dashboardService.recentChanges()).thenReturn(List.of());

        String fragment = mvc.perform(get("/dashboard/activity-feed")
                        .locale(Locale.SIMPLIFIED_CHINESE).with(as(false)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("暂无同步活动记录");
    }

    @Test
    void theSeriesEndpointAnswersWithTwentyFourPoints() throws Exception {
        when(dashboardService.activitySeries()).thenReturn(series());

        mvc.perform(get("/dashboard/activity-series").with(as(false)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.points", hasSize(24)))
                .andExpect(jsonPath("$.points[23].syncChanges").value(7))
                .andExpect(jsonPath("$.points[23].syncRows").value(70))
                .andExpect(jsonPath("$.points[23].etlChanges").value(2))
                .andExpect(jsonPath("$.points[23].etlRows").value(20))
                .andExpect(jsonPath("$.points[23].label").value("10:00"))
                .andExpect(jsonPath("$.totalSyncChanges").value(7))
                .andExpect(jsonPath("$.totalSyncRows").value(70))
                .andExpect(jsonPath("$.totalEtlChanges").value(2))
                .andExpect(jsonPath("$.totalEtlRows").value(20));
    }

    /** A seeded-password account sees an empty page, so nothing may start polling. */
    @Test
    void theDefaultPasswordAccountGetsNoLiveDashboard() throws Exception {
        String html = mvc.perform(get("/dashboard").locale(Locale.SIMPLIFIED_CHINESE).with(as(true)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("stat-grid")
                .doesNotContain("activity-chart")
                .doesNotContain("activity-feed");
    }

    private static DashboardService.Stats stats() {
        DashboardService.Stats stats = new DashboardService.Stats();
        stats.setProjectCount(3);
        stats.setDatabaseCount(4);
        stats.setSyncedTableCount(128);
        stats.setEtlTaskCount(5);
        stats.setEnabledEtlTaskCount(2);
        ChangeLog log = new ChangeLog();
        log.setId(9L);
        log.setProjectId(1L);
        log.setObjectType(ObjectType.DATA);
        log.setObjectName("customers");
        log.setChangeType(ChangeType.INSERT);
        log.setSuccess(true);
        log.setAffectedRows(4);
        stats.setRecentChanges(List.of(log));
        return stats;
    }

    private static DashboardService.MultiSeriesActivity series() {
        List<DashboardService.SeriesPoint> points = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            points.add(new DashboardService.SeriesPoint("09:00", 0, 0, 0, 0));
        }
        points.add(new DashboardService.SeriesPoint("10:00", 7, 70, 2, 20));
        return new DashboardService.MultiSeriesActivity(points, 7, 70, 2, 20);
    }

    private static RequestPostProcessor as(boolean usingDefaultPassword) {
        return as(UserRole.ADMIN, usingDefaultPassword);
    }

    private static RequestPostProcessor as(UserRole role, boolean usingDefaultPassword) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, usingDefaultPassword);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
