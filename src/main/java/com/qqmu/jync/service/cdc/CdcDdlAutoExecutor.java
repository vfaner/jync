package com.qqmu.jync.service.cdc;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.qqmu.jync.service.sync.SkippedDdlEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies the safe subset of CDC DDL automatically: only ALTER TABLE ... ADD COLUMN
 * statements whose columns are nullable and carry no key/identity/generated clause, and
 * only for single-target projects with the project switch enabled. Everything else keeps
 * the normal pending DDL task for human review.
 */
@Slf4j
@Component
public class CdcDdlAutoExecutor {

    private final CdcDdlTaskService tasks;

    public CdcDdlAutoExecutor(CdcDdlTaskService tasks) {
        this.tasks = tasks;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSkippedDdl(SkippedDdlEvent event) {
        try {
            tasks.tryAutoAddColumn(event.logId(), event.sql());
        } catch (RuntimeException e) {
            // Never turn an auto-apply failure into a consumer failure: the task stays
            // pending and the administrator can run it manually.
            log.warn("Automatic CDC ADD COLUMN failed for change-log entry {}: {}",
                    event.logId(), e.toString());
        }
    }
}
