package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * LogMiner 每一轮都要重新 ADD_LOGFILE → START_LOGMNR → 开游标 → END_LOGMNR。空闲项目
 * 按固定 1s 轮询,等于每秒在源端做一轮日志挖掘;按阶梯把「连续空轮」拉到 5s,能把空闲
 * 开销降到 1/5,一旦有任何一轮捞到行立即回到配置的轮询间隔,峰值延迟不受影响。
 */
class CdcIdleLadderTest {

    private CdcIdleLadder ladder() {
        return new CdcIdleLadder(1_000L, CdcIdleLadder.DEFAULT_CEILING_MS);
    }

    @Test
    void aSingleEmptyCycleWaitsTheConfiguredIdlePoll() {
        assertThat(ladder().nextDelayMs(false)).isEqualTo(1_000L);
    }

    @Test
    void consecutiveEmptyCyclesDoubleUpToTheCeiling() {
        CdcIdleLadder ladder = ladder();

        assertThat(ladder.nextDelayMs(false)).isEqualTo(1_000L);
        assertThat(ladder.nextDelayMs(false)).isEqualTo(2_000L);
        assertThat(ladder.nextDelayMs(false)).isEqualTo(4_000L);
        assertThat(ladder.nextDelayMs(false)).isEqualTo(5_000L);
        assertThat(ladder.nextDelayMs(false)).isEqualTo(5_000L);
    }

    @Test
    void aCycleThatAppliedRowsReturnsToTheConfiguredPoll() {
        CdcIdleLadder ladder = ladder();
        ladder.nextDelayMs(false);
        ladder.nextDelayMs(false);
        ladder.nextDelayMs(false);

        // 繁忙轮立即开下一轮
        assertThat(ladder.nextDelayMs(true)).isZero();
        // 之后的空轮从配置值重新开始,而不是接着 4s/5s
        assertThat(ladder.nextDelayMs(false)).isEqualTo(1_000L);
        assertThat(ladder.nextDelayMs(false)).isEqualTo(2_000L);
    }

    @Test
    void resetDropsBackToTheConfiguredPoll() {
        CdcIdleLadder ladder = ladder();
        ladder.nextDelayMs(false);
        ladder.nextDelayMs(false);

        // 失败轮由重连退避控制等待,空闲阶梯必须清零,恢复后的第一轮立即被探测
        ladder.reset();

        assertThat(ladder.nextDelayMs(false)).isEqualTo(1_000L);
    }

    @Test
    void aConfiguredPollAboveTheCeilingIsRespected() {
        CdcIdleLadder slow = new CdcIdleLadder(10_000L, 5_000L);

        assertThat(slow.nextDelayMs(false)).isEqualTo(10_000L);
        assertThat(slow.nextDelayMs(false)).isEqualTo(10_000L);
    }

    @Test
    void theBaseIsClampedToTheSafetyFloor() {
        assertThat(new CdcIdleLadder(1L, 5_000L).nextDelayMs(false))
                .isEqualTo(CdcReconnectBackoff.MIN_IDLE_POLL_MS);
    }
}
