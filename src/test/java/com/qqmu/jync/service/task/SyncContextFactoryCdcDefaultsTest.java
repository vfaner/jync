package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;

/**
 * 日志 CDC 的基线是唯一一次全量加载,而表单在 CDC 模式下把表结构、表数据两个开关藏了
 * 起来。隐藏不等于没有作用:保存值若停在关闭(任务原本只同步结构,之后切成 CDC),基线
 * 就会跳过数据加载 —— 位点之前已存在的行永远进不了目标库,而流式回放只覆盖位点之后的
 * 变更。工厂在建 ctx 时把这两项视为开启;不写回配置,切回轮询时用户原来的选择仍然保留。
 *
 * <p>索引不在此列:它不影响基线正确性,只影响目标表的写入成本与空间,而且用户经常手工
 * 建表、自带索引,或在装载期间刻意不维护二级索引。保存值关着就必须关着,不能被 CDC
 * 强制打开。
 */
class SyncContextFactoryCdcDefaultsTest {

    private static SyncConfig allOff(CaptureStrategy strategy) {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        config.setSyncStructure(false);
        config.setSyncData(false);
        config.setSyncIndexes(false);
        return config;
    }

    @Test
    void everyLogCdcEngineForcesStructureAndDataButHonoursTheIndexSwitch() {
        for (CaptureStrategy strategy : new CaptureStrategy[] {
                CaptureStrategy.BINLOG_CDC, CaptureStrategy.LOGMINER_CDC,
                CaptureStrategy.PG_LOGICAL_CDC, CaptureStrategy.SQLSERVER_CDC,
                CaptureStrategy.YSTREAM_CDC }) {
            SyncConfig config = allOff(strategy);

            SyncContextFactory.applyCdcBaselineDefaults(config);

            assertThat(config.isSyncStructure()).as(strategy.name()).isTrue();
            assertThat(config.isSyncData()).as(strategy.name()).isTrue();
            // The switch is visible in CDC mode and means what it says: a user who turned
            // indexes off (manual tables, own indexes, or simply not wanting the load to pay
            // for index maintenance) must not get them created anyway.
            assertThat(config.isSyncIndexes()).as(strategy.name()).isFalse();
        }
    }

    @Test
    void aSavedIndexSwitchStaysOnForCdc() {
        SyncConfig config = allOff(CaptureStrategy.LOGMINER_CDC);
        config.setSyncIndexes(true);

        SyncContextFactory.applyCdcBaselineDefaults(config);

        assertThat(config.isSyncIndexes()).isTrue();
    }

    @Test
    void pollingStrategiesKeepExactlyWhatTheUserSaved() {
        for (CaptureStrategy strategy : new CaptureStrategy[] {
                CaptureStrategy.POLLING, CaptureStrategy.FULL_COMPARE }) {
            SyncConfig config = allOff(strategy);

            SyncContextFactory.applyCdcBaselineDefaults(config);

            assertThat(config.isSyncStructure()).as(strategy.name()).isFalse();
            assertThat(config.isSyncData()).as(strategy.name()).isFalse();
            assertThat(config.isSyncIndexes()).as(strategy.name()).isFalse();
        }
    }

    @Test
    void aCdcProjectKeepsItsDangerousAndBaselineOnlyFlagsUntouched() {
        SyncConfig config = allOff(CaptureStrategy.BINLOG_CDC);
        config.setAllowDrop(true);
        config.setSyncDeletes(true);
        config.setSyncViews(false);
        config.setSyncProcedures(false);

        SyncContextFactory.applyCdcBaselineDefaults(config);

        assertThat(config.isAllowDrop()).isTrue();
        assertThat(config.isSyncDeletes()).isTrue();
        assertThat(config.isSyncViews()).isFalse();
        assertThat(config.isSyncProcedures()).isFalse();
    }
}
