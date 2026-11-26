package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SizeFormatTest {

    @Test
    void smallValuesStayInBytes() {
        assertThat(SizeFormat.bytes(0)).isEqualTo("0 B");
        assertThat(SizeFormat.bytes(767)).isEqualTo("767 B");
    }

    @Test
    void kilobytesAndAMixedLowerUnit() {
        assertThat(SizeFormat.bytes(16_384)).isEqualTo("16 KB");
        // 415,487 = 405 KB + 767 B — the same two-tier form the metrics chart uses.
        assertThat(SizeFormat.bytes(415_487)).isEqualTo("405 KB 767 B");
    }

    @Test
    void largerValuesUseTheNextUnit() {
        assertThat(SizeFormat.bytes((1L << 30) + (2L << 20))).isEqualTo("1 GB 2 MB");
    }
}
