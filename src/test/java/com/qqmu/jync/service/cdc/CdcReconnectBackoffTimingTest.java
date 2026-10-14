package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 轮询式 CDC(LogMiner / PG 逻辑复制 / SQL Server CDC)每轮结束后的等待决策:
 * 失败走指数退避;成功且捞到数据立即开下一轮(繁忙期不人为限速,对齐 Debezium 的
 * continuous mining 节奏);成功但空闲才休眠 idlePollMs。旧实现对「成功且有数据」
 * 也取 reconnect backoff,繁忙时每轮硬等 1s 且 attempt 随繁忙轮次翻倍,把高峰期
 * 延迟和吞吐都做坏了。
 */
class CdcReconnectBackoffTimingTest {

    private CdcReconnectBackoff backoff() {
        return new CdcReconnectBackoff(1_000L, 60_000L);
    }

    @Test
    void busyCycleContinuesImmediatelyWithoutTouchingBackoff() {
        CdcReconnectBackoff backoff = backoff();

        // 连续三轮都捞到数据:零等待
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(null, true, backoff, 1_000L)).isZero();
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(null, true, backoff, 1_000L)).isZero();
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(null, true, backoff, 1_000L)).isZero();

        // 繁忙轮不得消耗退避计数:紧接着的失败仍从初始 1s 开始,不是 2s/4s
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(new RuntimeException("boom"),
                false, backoff, 1_000L)).isEqualTo(1_000L);
    }

    @Test
    void idleCycleSleepsTheIdleInterval() {
        CdcReconnectBackoff backoff = backoff();
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(null, false, backoff, 250L))
                .isEqualTo(250L);
        // 空闲轮同样不推进失败退避
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(new RuntimeException("boom"),
                false, backoff, 250L)).isEqualTo(1_000L);
    }

    @Test
    void failedCycleAlwaysBacksOffEvenWhenRowsWereDrained() {
        // 失败优先:即便本轮捞到了行(异常可能发生在 flush 阶段),也必须退避,不能 0 等待热循环
        CdcReconnectBackoff backoff = backoff();
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(new RuntimeException("boom"),
                true, backoff, 1_000L)).isEqualTo(1_000L);
        assertThat(CdcReconnectBackoff.nextCycleDelayMs(new RuntimeException("boom"),
                true, backoff, 1_000L)).isEqualTo(2_000L);
    }

    @Test
    void effectiveIdlePollFallsBackToEngineDefault() {
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(null, 1_000L)).isEqualTo(1_000L);
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(0L, 500L)).isEqualTo(500L);
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(-10L, 1_000L)).isEqualTo(1_000L);
    }

    @Test
    void effectiveIdlePollClampsToTheSafetyFloor() {
        // 防脚枪:过小的轮询间隔等于对源库做紧密挖掘循环,服务端读侧统一夹到 50ms 下限,
        // 不依赖界面校验(直接 API/旧客户端也可能写入)。
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(1L, 1_000L))
                .isEqualTo(CdcReconnectBackoff.MIN_IDLE_POLL_MS);
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(49L, 1_000L)).isEqualTo(50L);
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(50L, 1_000L)).isEqualTo(50L);
        assertThat(CdcReconnectBackoff.effectiveIdlePollMs(250L, 1_000L)).isEqualTo(250L);
    }
}
