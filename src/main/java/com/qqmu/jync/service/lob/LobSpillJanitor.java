package com.qqmu.jync.service.lob;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Startup/shutdown lifecycle for the spill directory.
 *
 * <p>A process killed mid-transaction leaves {@code jync-lob-*} files behind with nobody
 * tracking them. Startup deletes the ones older than
 * {@code sync.lob.spill-retention-hours} (files from another, still-running JVM are young
 * and survive). Shutdown closes every session the JVM still has open, so a clean stop
 * leaves the directory empty.
 */
@Slf4j
@Component
public class LobSpillJanitor {

    private final LobSpillStore store;
    private final long retentionHours;

    public LobSpillJanitor(SyncProperties properties) {
        this.store = LobSpillStore.from(properties);
        this.retentionHours = properties.getLob().getSpillRetentionHours();
    }

    @PostConstruct
    public void sweepOnStartup() {
        int deleted = store.sweepOldFiles(retentionHours);
        if (deleted > 0) {
            log.info("Deleted {} stale LOB spill file(s) from a previous run", deleted);
        }
    }

    @PreDestroy
    public void closeOpenSessions() {
        LobSpillStore.closeAllActiveSessions();
    }
}
