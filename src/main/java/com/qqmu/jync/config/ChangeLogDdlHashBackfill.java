package com.qqmu.jync.config;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.util.DigestUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Fills {@code change_log.details_hash} on skipped-DDL tasks filed before the column existed.
 *
 * <p>The hash is the identity the CDC consumers compare after a restart: the in-memory dedupe
 * map dies with the process, so the surviving row is the only record that an event was already
 * filed. A row without a hash can never match, and every reconnect would file the same DDL
 * again — exactly the duplicate this column exists to prevent.
 *
 * <p>The statement is read and hashed in Java: an {@code =} or {@code like} predicate on the
 * {@code details} LOB is what broke on PostgreSQL, where an unbounded {@code @Lob String} is
 * an {@code oid}. The backfill is idempotent (a hashed row is no longer a candidate) and runs
 * on every start.
 *
 * <p>A failure is logged, not rethrown: the task list and the projection gate read
 * {@code resolution_status}, so a missing hash only risks one duplicate filing after a
 * reconnect — not a reason to refuse to boot.
 */
@Component
@Slf4j
public class ChangeLogDdlHashBackfill implements ApplicationRunner {

    private final ChangeLogRepository repository;
    private final TransactionTemplate transactionTemplate;

    public ChangeLogDdlHashBackfill(ChangeLogRepository repository,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer updated = transactionTemplate.execute(status -> backfill());
            if (updated != null && updated > 0) {
                log.info("Backfilled details_hash on {} skipped-DDL task row(s)", updated);
            }
        } catch (RuntimeException e) {
            log.warn("Could not backfill change_log.details_hash; a DDL already on record may "
                    + "be filed once more after a reconnect: {}", e.getMessage());
        }
    }

    /** Package-private so the test can run it inside its own transaction. */
    int backfill() {
        List<ChangeLog> legacy = repository.findByResolutionStatusIsNotNullAndDetailsHashIsNull();
        for (ChangeLog row : legacy) {
            String details = row.getDetails() == null ? "" : row.getDetails();
            row.setDetailsHash(DigestUtil.sha256Hex(details));
        }
        repository.saveAll(legacy);
        return legacy.size();
    }
}
