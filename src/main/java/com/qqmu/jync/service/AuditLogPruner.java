package com.qqmu.jync.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.repository.AuditEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Enforces the audit retention window that {@code sync.auditRetentionDays} promises.
 *
 * <p>The trail is append-only by default, but "forever" is not a retention policy: without a
 * window the metadata store grows without bound on a busy deployment. The pruner trims only
 * rows older than the window; the administrator's date purge remains the only way to remove
 * newer history, and that act records itself. A retention of zero or less disables cleanup.
 *
 * <p>Mirrors {@link ChangeLogPruner}: hourly, first run delayed past startup, failures logged
 * and swallowed so one bad run cannot cancel the schedule, and the transaction lives on the
 * repository method rather than here.
 */
@Component
@Slf4j
public class AuditLogPruner {

    private final AuditEventRepository auditEventRepository;
    private final SyncProperties properties;

    public AuditLogPruner(AuditEventRepository auditEventRepository, SyncProperties properties) {
        this.auditEventRepository = auditEventRepository;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 6 * 60 * 1000L, fixedDelay = 60 * 60 * 1000L)
    public void prune() {
        int retentionDays = properties.getAuditRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = auditEventRepository.deleteByOccurredAtBefore(cutoff);
            if (deleted > 0) {
                log.info("Pruned {} audit entries older than {} day(s)", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("Audit pruning failed, will retry on the next run: {}", e.getMessage());
        }
    }
}
