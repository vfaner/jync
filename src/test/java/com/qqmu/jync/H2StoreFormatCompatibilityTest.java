package com.qqmu.jync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The embedded metadata store must stay readable across the 1.x -> 2.x upgrade.
 *
 * <p>H2 2.2 changed the MVStore write format and refuses to open the files 2.1 wrote
 * ({@code Unsupported database file version}). Every 1.x deployment keeps its metadata at
 * {@code ./data/jync.mv.db}, so the Boot 4 default (2.4.x) would make a plain jar swap fail at
 * startup. The POM pins 2.1.214 until an explicit export/import migration exists; this test
 * fails the moment that pin is removed or raised without one.
 */
class H2StoreFormatCompatibilityTest {

    @Test
    @DisplayName("embedded H2 stays on the 2.1 line that reads 1.x metadata files")
    void embeddedH2StaysOnTheVersionThatReadsOneXStores() {
        Driver driver = new org.h2.Driver();

        assertThat(driver.getMajorVersion()).isEqualTo(2);
        assertThat(driver.getMinorVersion()).isEqualTo(1);
    }
}
