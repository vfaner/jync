package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 耗时列与任务摘要给人看，不给机器看：{@code 99550 ms} 要读成 {@code 1m 39s}。
 *
 * <p>单位用中性的 d/h/m/s（与摘要里本来就有的英文混排不冲突）；小于一秒时才保留毫秒，
 * 因为那种耗时多半是"建表 8ms"这类，换算成秒会变成一片 0s。秒及以下向下取整：这是已经
 * 流逝的时间，不四舍五入到尚未发生的值。
 */
class DurationFormatTest {

    @Test
    void underOneSecondStaysInMilliseconds() {
        assertThat(DurationFormat.format(8)).isEqualTo("8 ms");
        assertThat(DurationFormat.format(999)).isEqualTo("999 ms");
    }

    @Test
    void oneSecondAndUpReadsInSeconds() {
        assertThat(DurationFormat.format(1000)).isEqualTo("1s");
        assertThat(DurationFormat.format(1500)).as("向下取整").isEqualTo("1s");
        assertThat(DurationFormat.format(59_999)).isEqualTo("59s");
    }

    @Test
    void oneMinuteAndUpReadsInMinutesAndSeconds() {
        assertThat(DurationFormat.format(60_000)).isEqualTo("1m");
        assertThat(DurationFormat.format(99_550)).as("截图里的 99550 ms").isEqualTo("1m 39s");
        assertThat(DurationFormat.format(100_705)).as("截图里的 100705 ms").isEqualTo("1m 40s");
    }

    @Test
    void oneHourAndUpReadsInHoursAndMinutes() {
        assertThat(DurationFormat.format(3_600_000)).isEqualTo("1h");
        assertThat(DurationFormat.format(21_993_000)).as("6h 6m 33s").isEqualTo("6h 6m");
    }

    @Test
    void oneDayAndUpReadsInDaysHoursAndMinutes() {
        assertThat(DurationFormat.format(86_400_000)).isEqualTo("1d");
        assertThat(DurationFormat.format(5L * 86_400_000 + 6L * 3_600_000 + 3L * 60_000 + 12_000))
                .as("用户给的例子：5天6个小时3分钟").isEqualTo("5d 6h 3m");
    }

    @Test
    void zeroAndNegativeNeverPrintNonsense() {
        assertThat(DurationFormat.format(0)).isEqualTo("0 ms");
        assertThat(DurationFormat.format(-5)).isEqualTo("0 ms");
    }
}
