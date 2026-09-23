package com.qqmu.jync.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.repository.ChangeLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Enforces the change-log retention window that {@code sync.changeLogRetentionDays} promises.
 *
 * <p>Without this the audit trail — with its {@code @Lob} details column — grows the
 * file-based H2 store without bound. A retention of zero or less disables the cleanup.
 */
@Component
@Slf4j
public class ChangeLogPruner {

    private final ChangeLogRepository changeLogRepository;
    private final SyncProperties properties;

    public ChangeLogPruner(ChangeLogRepository changeLogRepository, SyncProperties properties) {
        this.changeLogRepository = changeLogRepository;
        this.properties = properties;
    }

    /**
     * Prunes hourly, with the first run delayed so it never competes with startup.
     *
     * <p>Failures are logged and swallowed: an exception escaping a fixed-delay task would
     * cancel every subsequent run. The transaction lives on the repository method,
     * deliberately not here, so this catch never has to deal with a rollback-only mark.
     */
    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = 60 * 60 * 1000L)
    public void prune() {
        int retentionDays = properties.getChangeLogRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = changeLogRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("Pruned {} change-log entries older than {} day(s)", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("Change-log pruning failed, will retry on the next run: {}", e.getMessage());
        }
    }
}
