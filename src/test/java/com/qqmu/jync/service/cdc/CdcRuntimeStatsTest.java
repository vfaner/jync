package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CdcRuntimeStatsTest {

    @Test
    void recordPositionScnExposedInSnapshot() {
        CdcRuntimeStats stats = new CdcRuntimeStats();
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
