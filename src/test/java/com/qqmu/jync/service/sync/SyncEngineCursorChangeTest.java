package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.monitor.CursorStrategy;

/** Lazy re-baselining must trigger only for polling tables whose cursor column actually moved. */
class SyncEngineCursorChangeTest {

    private static SyncProgress progress(String column) {
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue("2026-11-22 08:00:00");
        progress.setCursorColumn(column);
        return progress;
    }

    @Test
    void aDifferentEffectiveColumnReloads() {
        assertThat(SyncEngine.cursorColumnChanged(progress("UPDATE_TIME"),
                CursorStrategy.timestamp("UPDATE_STAMP", Types.TIMESTAMP, "priority"),
                new SyncConfig())).isTrue();
    }

    @Test
    void caseDifferencesDoNotReload() {
        assertThat(SyncEngine.cursorColumnChanged(progress("update_stamp"),
                CursorStrategy.timestamp("UPDATE_STAMP", Types.TIMESTAMP, "priority"),
                new SyncConfig())).isFalse();
    }

    @Test
    void anUnsyncedTableDoesNotReload() {
        SyncProgress progress = progress(null);
        progress.setLastSyncValue(null);
        assertThat(SyncEngine.cursorColumnChanged(progress,
                CursorStrategy.timestamp("UPDATE_STAMP", Types.TIMESTAMP, "priority"),
                new SyncConfig())).isFalse();
    }

    @Test
    void aLegacyProgressRowWithoutAColumnDoesNotMassReloadOnUpgrade() {
        SyncProgress progress = progress(null);
        progress.setInitialLoadDone(true);
        assertThat(SyncEngine.cursorColumnChanged(progress,
                CursorStrategy.identity("ID", Types.BIGINT, "test"),
                new SyncConfig())).isFalse();
    }

    @Test
    void aLegacyRowReloadsOnceAPriorityIsConfigured() {
        SyncConfig config = new SyncConfig();
        config.setCursorColumnPriority(List.of("ID"));
        assertThat(SyncEngine.cursorColumnChanged(progress(null),
                CursorStrategy.identity("ID", Types.BIGINT, "priority"), config)).isTrue();
    }

    @Test
    void logCdcNeverReloads() {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        assertThat(SyncEngine.cursorColumnChanged(progress("OLD_COLUMN"),
                CursorStrategy.timestamp("NEW_COLUMN", Types.TIMESTAMP, "priority"),
                config)).isFalse();
    }

    @Test
    void fullCompareNeverReloadsThroughThisPath() {
        assertThat(SyncEngine.cursorColumnChanged(progress("OLD_COLUMN"),
                CursorStrategy.fullCompare("test"), new SyncConfig())).isFalse();
    }
}
