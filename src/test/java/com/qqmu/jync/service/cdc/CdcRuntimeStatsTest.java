package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class CdcRuntimeStatsTest {

    // —— 延迟:衡量「处理时已经迟了多久」,不是「距上一条事件多久」——

    /**
     * 源库空闲时一条事件也不来。按「距上一条事件多久」算延迟,这个数字每分钟 +60 秒,
     * 一个完全跟上的消费者会被画成永远落后;健康的空闲任务不该看起来像卡死了。
     */
    @Test
    void anIdleStreamDoesNotAccumulateLag() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);

        stats.touchEvent(clock.get());
        assertThat(stats.lagSeconds()).isZero();

        clock.addAndGet(Duration.ofMinutes(10).toMillis());
        assertThat(stats.lagSeconds()).isZero();
    }

    @Test
    void lagIsHowLateTheNewestEventWasWhenItWasProcessed() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);

        // 连接时产生的、90 秒后才被处理的事件 —— 这就是真实的落后量。
        long eventAt = clock.get();
        clock.addAndGet(90_000L);
        stats.touchEvent(eventAt);
        assertThat(stats.lagSeconds()).isEqualTo(90L);
    }

    /**
     * 重连后消费者从持久化位点重放:这些事件本来就产生于本次连接之前,是「追赶」,
     * 不是「现在落后」。把它们算进延迟,源库一空闲,面板就会永远停在一个吓人的
     * 数字上(实测重启后 19 分钟的源库显示「落后 1,135 秒」),再也没有新事件来覆盖它。
     */
    @Test
    void aReplayedPreConnectionEventIsCatchUpNotCurrentLag() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);

        // 源库 10 分钟没有变更,持久化位点停在那一刻;重启后先重放这条旧事件。
        clock.addAndGet(Duration.ofMinutes(10).toMillis());
        stats.setConnected(true);
        stats.touchEvent(clock.get() - Duration.ofMinutes(10).toMillis());

        assertThat(stats.lagSeconds()).isZero();
        // 但「上次事件」仍要前进:我们确实在这一刻应用了一条事件。
        assertThat(stats.snapshot().get("lastEventAt"))
                .isEqualTo(Instant.ofEpochMilli(clock.get()));
    }

    /**
     * 提交 / DDL / 心跳这类连接级记录没有源时间戳。调用方仍要让「上次事件」前进,
     * 但不能把刚量到的延迟抹成 0 —— 一条提交记录不证明流已经追上。
     */
    @Test
    void touchActivityMovesLastEventWithoutErasingMeasuredLag() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);

        long eventAt = clock.get();
        clock.addAndGet(90_000L);
        stats.touchEvent(eventAt);
        assertThat(stats.lagSeconds()).isEqualTo(90L);

        clock.addAndGet(5_000L);
        stats.touchActivity();

        assertThat(stats.lagSeconds()).isEqualTo(90L);
        assertThat(stats.snapshot().get("lastEventAt"))
                .isEqualTo(Instant.ofEpochMilli(clock.get()));
    }

    /** 心跳 / 格式描述事件的源时间戳可能是 0;把它当成 1970 年会读出天文数字。 */
    @Test
    void anUnknownOrFutureEventTimestampIsNotLag() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);

        stats.touchEvent(0L);
        assertThat(stats.lagSeconds()).isZero();

        stats.touchEvent(clock.get() + 5_000L);
        assertThat(stats.lagSeconds()).isZero();
    }

    @Test
    void aDisconnectedConsumerReportsUnknownLag() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);
        stats.touchEvent(clock.get());
        stats.setConnected(false);

        assertThat(stats.lagSeconds()).isEqualTo(-1L);
    }

    /**
     * 面板不再显示「落后 N 秒」——空闲源库会让这个数字读起来像永远追不上。改为显示
     * 「上次事件」的处理时刻:没事件就是 —,有事件就是那一刻的墙钟时间。
     */
    @Test
    void theSnapshotCarriesWhenTheLastEventWasProcessed() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);
        stats.setConnected(true);
        assertThat(stats.snapshot().get("lastEventAt")).isNull();

        clock.addAndGet(5_000L);
        stats.touchEvent(clock.get());
        assertThat(stats.snapshot().get("lastEventAt"))
                .isEqualTo(Instant.ofEpochMilli(clock.get()));
    }

    @Test
    void recordPositionScnExposedInSnapshot() {        CdcRuntimeStats stats = new CdcRuntimeStats();
        assertThat(stats.getPositionScn()).isNull();
        assertThat(stats.snapshot().get("positionScn")).isNull();

        stats.recordPositionScn(2_111_043L);
        assertThat(stats.getPositionScn()).isEqualTo(2_111_043L);
        assertThat(stats.snapshot().get("positionScn")).isEqualTo(2_111_043L);
    }

    @Test
    void scnAndBinlogPositionsAreIndependent() {
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.recordPosition("mysql-bin.000001", 123L, "uuid:1-1");
        // Recording a binlog position must not populate the SCN column.
        assertThat(stats.getPositionScn()).isNull();

        stats.recordPositionScn(77L);
        // And vice versa.
        assertThat(stats.getPositionFile()).isEqualTo("mysql-bin.000001");
        assertThat(stats.getPositionPos()).isEqualTo(123L);
    }

    // —— PG 槽位 WAL 滞留量:面板要能看到「槽子把 WAL 攒到多大」——

    @Test
    void retainedWalIsUnknownUntilMeasured() {
        CdcRuntimeStats stats = new CdcRuntimeStats();
        assertThat(stats.getRetainedWalBytes()).isNull();
        assertThat(stats.getRetainedWalLabel()).isNull();
        // The panel hides the row entirely when the value is unknown, so the snapshot
        // must carry a null — not a zero that reads as "measured, nothing held".
        assertThat(stats.snapshot().get("retainedWalBytes")).isNull();
    }

    @Test
    void retainedWalIsFormattedAndFlaggedOverTheThreshold() {
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.recordRetainedWal(1_500_000_000L, 4_000_000_000L);
        assertThat(stats.getRetainedWalLabel()).isEqualTo("1.4 GiB");
        assertThat(stats.isRetainedWalHigh()).isFalse();
        assertThat(stats.snapshot().get("retainedWalBytes")).isEqualTo(1_500_000_000L);
        assertThat(stats.snapshot().get("retainedWalHigh")).isEqualTo(false);

        stats.recordRetainedWal(9_000_000_000L, 4_000_000_000L);
        assertThat(stats.getRetainedWalLabel()).isEqualTo("8.4 GiB");
        assertThat(stats.isRetainedWalHigh()).isTrue();
        assertThat(stats.snapshot().get("retainedWalHigh")).isEqualTo(true);

        stats.recordRetainedWal(2_000_000L, 4_000_000_000L);
        assertThat(stats.getRetainedWalLabel()).isEqualTo("1.9 MiB");
        stats.recordRetainedWal(512L, 4_000_000_000L);
        assertThat(stats.getRetainedWalLabel()).isEqualTo("512 B");
    }

    @Test
    void aZeroOrNegativeThresholdNeverWarns() {
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.recordRetainedWal(9_000_000_000L, 0L);
        assertThat(stats.isRetainedWalHigh()).isFalse();
    }

    /**
     * 面板只有人盯着才有用;槽子把源库磁盘攒满通常发生在没人看页面的时候,所以每次
     * 「越过阈值」都必须配一条 WARN。判定放这里(而不是调用方),和 missing-LOB 的
     * {@code recordMissingLob} 同一个理由:节流逻辑可以不抓日志就测。
     */
    @Test
    void theCrossingOfTheThresholdIsReportedOncePerCrossing() {
        CdcRuntimeStats stats = new CdcRuntimeStats();

        assertThat(stats.recordRetainedWal(1_000L, 4_000_000_000L))
                .as("below the threshold: nothing to warn about").isFalse();
        assertThat(stats.recordRetainedWal(5_000_000_000L, 4_000_000_000L))
                .as("first sample above the threshold").isTrue();
        assertThat(stats.recordRetainedWal(6_000_000_000L, 4_000_000_000L))
                .as("still above: this crossing was already announced").isFalse();
        assertThat(stats.recordRetainedWal(1_000L, 4_000_000_000L))
                .as("recovered: silent").isFalse();
        assertThat(stats.recordRetainedWal(5_000_000_000L, 4_000_000_000L))
                .as("crossed again after recovering: warn again").isTrue();
    }

    @Test
    void positionMarkScnVariant() {
        CdcPositionMark scnMark = CdcPositionMark.ofScn(9L);
        assertThat(scnMark.getScn()).isEqualTo(9L);
        assertThat(scnMark.getFile()).isNull();
        assertThat(scnMark.getPos()).isZero();
        assertThat(scnMark.getGtidSet()).isNull();

        CdcPositionMark binlogMark = new CdcPositionMark("mysql-bin.1", 100L, null);
        assertThat(binlogMark.getScn()).isNull();
    }
}
