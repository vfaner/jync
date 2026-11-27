package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CdcPositionExternalBaselineTest {

    @Test
    void tableNamesRoundTripAndMatchCaseInsensitively() {
        CdcPosition position = new CdcPosition();
        position.setExternalBaselineTableNames(List.of(" Orders ", "customers", "customers"));

        assertThat(position.hasExternalBaseline()).isTrue();
        assertThat(position.externalBaselineIsAllTables()).isFalse();
        assertThat(position.externalBaselineCovers("orders")).isTrue();
        assertThat(position.externalBaselineCovers("CUSTOMERS")).isTrue();
        assertThat(position.externalBaselineCovers("products")).isFalse();
        assertThat(position.externalBaselineTableNames()).containsExactly("Orders", "customers");

        position.clearExternalBaseline();
        assertThat(position.hasExternalBaseline()).isFalse();
        assertThat(position.externalBaselineCovers("orders")).isFalse();
    }

    @Test
    void theAllTablesSentinelCoversEveryName() {
        CdcPosition position = new CdcPosition();
        position.setExternalBaselineTableNames(List.of(CdcPosition.EXTERNAL_ALL_TABLES));

        assertThat(position.externalBaselineIsAllTables()).isTrue();
        assertThat(position.externalBaselineCovers("anything")).isTrue();
    }
}
