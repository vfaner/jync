package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MariaDB must report <em>changed</em> rows, not found rows, for an upsert to be a no-op.
 *
 * <p>The cursor's safety margin replays the newest rows of every window on the next cycle.
 * With MariaDB Connector/J's default (found-rows) semantics an identical
 * {@code ON DUPLICATE KEY UPDATE} answers 1, which Jync cannot tell from an insert; the idle
 * cycle then reports a change, files a change-log entry and inflates the synced total.
 * {@code MariaDbPollingIT} reproduces exactly that. The flag also enables the 1-insert /
 * 2-update classification MySQL already has.
 */
class MariaDbAffectedRowsTest {

    @Test
    void mariaDbAsksTheDriverForAffectedRows() {
        assertThat(DatabaseType.MARIADB.getUrlTemplate()).contains("useAffectedRows=true");
        assertThat(DatabaseType.MARIADB.upsertCountsDistinguishInsertFromUpdate()).isTrue();
    }
}
