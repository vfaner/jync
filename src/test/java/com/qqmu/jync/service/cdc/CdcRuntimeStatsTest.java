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
