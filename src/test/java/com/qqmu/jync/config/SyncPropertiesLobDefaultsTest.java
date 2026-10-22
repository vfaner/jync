package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * LOB defaults are safe for an existing project: streaming on, but the thresholds high
 * enough that ordinary VARCHAR/TEXT columns keep batching, and a spill directory under the
 * system temp dir that needs no configuration.
 */
class SyncPropertiesLobDefaultsTest {

    @Test
    void streamingIsOnByDefault() {
        SyncProperties properties = new SyncProperties();
        assertThat(properties.getLob().isStreamingEnabled()).isTrue();
        assertThat(properties.getLob().isEnableSourceStreaming()).isTrue();
    }

    @Test
    void thresholdsHaveDocumentedDefaults() {
        SyncProperties.Lob lob = new SyncProperties().getLob();
        assertThat(lob.getInlineMaxBytes()).isEqualTo(65_536L);
        assertThat(lob.getCdcSpillThresholdBytes()).isEqualTo(1_048_576L);
        assertThat(lob.getBatchMaxBytes()).isEqualTo(16_777_216L);
        assertThat(lob.getForceInlineMaxBytes()).isEqualTo(1_048_576L);
        assertThat(lob.getSpillMaxBytes()).isEqualTo(1_073_741_824L);
        assertThat(lob.getSpillRetentionHours()).isEqualTo(24L);
        assertThat(lob.getMetricsLogIntervalMs()).isEqualTo(60_000L);
        assertThat(lob.isOracleEmptyStringAsEmptyClob()).isTrue();
    }

    @Test
    void spillDirectoryDefaultsUnderTheSystemTempDir() {
        Path dir = Path.of(new SyncProperties().getLob().getSpillDir());
        assertThat(dir.isAbsolute()).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("jync-lob");
    }
}
