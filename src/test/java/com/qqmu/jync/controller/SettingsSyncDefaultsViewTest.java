package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.version.VersionInfo;
import com.qqmu.jync.service.version.VersionService;

/**
 * The settings page is the only place an administrator can see the effective global
 * defaults. Several generations of tuning knobs were added after the page was written
 * (full-load batch/fetch/parallelism, chunking, deferred indexes, pool size, timeouts,
 * CDC backoff), and they were silently absent from it. These tests pin the three cards so
 * a new knob cannot be added to {@code SyncProperties} and stay invisible again.
 */
@WebMvcTest(SettingsController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "sync.reconcile-interval-ms=30000",
        "sync.full-load-batch-size=2000",
        "sync.full-load-fetch-size=2000",
        "sync.full-load-parallelism=2",
        "sync.chunk-size=100000",
        "sync.defer-secondary-indexes=true",
        "sync.oracle-initial-load-insert-first=true",
        "sync.max-pool-size=5",
        "sync.row-count-audit-interval-ms=60000",
        "sync.query-timeout-seconds=300",
        "sync.metadata-query-timeout-seconds=60",
        "sync.shutdown-wait-ms=20000",
        "sync.cdc.reconnect-backoff-max-ms=60000",
        "sync.cdc.supervise-interval-ms=10000",
        "sync.cdc.change-window-ms=10000",
        "sync.cdc.logminer.batch-size=10000",
        "sync.cdc.pg.batch-size=1000",
        "sync.cdc.mssql.batch-size=1000"})
class SettingsSyncDefaultsViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SyncScheduler scheduler;
    @MockBean
    private SyncLockService lockService;
    @MockBean
    private VersionService versionService;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render(Locale locale) throws Exception {
        when(scheduler.scheduledProjectIds()).thenReturn(List.of());
        when(lockService.getOwnerId()).thenReturn("testhost:123");
        when(versionService.snapshot()).thenReturn(VersionInfo.builder()
                .currentVersion("1.7.7")
                .state(VersionInfo.State.UP_TO_DATE)
                .latestVersion("1.7.7").latestTag("v1.7.7")
                .build());
        return mvc.perform(get("/settings").locale(locale).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fullLoadDefaultsHaveTheirOwnCard() throws Exception {
        String page = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(page).contains("全量加载性能");
        assertThat(page).contains("全量批次大小");
        assertThat(page).contains("全量读取批量");
        assertThat(page).contains("全量并行度");
        assertThat(page).contains("分块行数");
        assertThat(page).contains("延迟创建二级索引");
        assertThat(page).contains("Oracle 空表全量用 INSERT");
        assertThat(page).contains("100000");
    }

    @Test
    void concurrencyCardShowsTheOperationalBounds() throws Exception {
        String page = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(page).contains("每库连接池上限");
        assertThat(page).contains("行数审计间隔");
        assertThat(page).contains("数据查询超时");
        assertThat(page).contains("元数据查询超时");
        assertThat(page).contains("停机等待时长");
        assertThat(page).contains("调度对账间隔");
        assertThat(page).contains("60000");
    }

    @Test
    void cdcEngineParametersHaveASummaryCard() throws Exception {
        String page = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(page).contains("CDC 引擎参数");
        assertThat(page).contains("重连退避上限");
        assertThat(page).contains("消费者监督间隔");
        assertThat(page).contains("变更聚合窗口");
        assertThat(page).contains("LogMiner 批次");
        assertThat(page).contains("PostgreSQL 逻辑复制批次");
        assertThat(page).contains("SQL Server CDC 批次");
    }

    /**
     * v1.7.8 added a whole LOB subsystem (streaming, spill-to-disk, source read
     * transactions, the LogMiner policy) whose knobs a user can only discover from this
     * page or the YAML. They get their own card, translated in both languages.
     */
    @Test
    void largeObjectParametersHaveTheirOwnCard() throws Exception {
        String page = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(page).contains("大字段与流式");
        assertThat(page).contains("流式读写");
        assertThat(page).contains("内存内联阈值");
        assertThat(page).contains("CDC 落盘阈值");
        assertThat(page).contains("单批字节预算");
        assertThat(page).contains("源端流式读取");
        assertThat(page).contains("落盘目录");
        assertThat(page).contains("落盘容量上限");
        assertThat(page).contains("清理保留期");
        assertThat(page).contains("LogMiner 缺失 LOB 策略");
        assertThat(page).contains("65536");
        assertThat(page).contains("skip");
    }

    @Test
    void englishPageTranslatesEveryNewLabel() throws Exception {
        String page = render(Locale.ENGLISH);

        assertThat(page).contains("Full-load performance");
        assertThat(page).contains("CDC engine parameters");
        assertThat(page).contains("Large objects and streaming");
        assertThat(page).contains("LogMiner missing-LOB policy");
        assertThat(page).doesNotContain("全量加载性能");
        assertThat(page).doesNotContain("大字段与流式");
        assertThat(page).doesNotContain("??settings.");
    }
}
