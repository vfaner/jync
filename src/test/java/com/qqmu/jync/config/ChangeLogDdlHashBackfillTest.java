package com.qqmu.jync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.util.DigestUtil;

/**
 * Rows filed before {@code details_hash} existed carry no hash; without a backfill the next
 * restart re-files the very DDL they already describe and the operator gets a duplicate task.
 *
 * <p>The backfill reads the {@code @Lob} text in Java because a {@code like} predicate on it
 * is exactly what fails on PostgreSQL ({@code operator does not exist: oid ~~ text}). It is
 * idempotent: once hashed, a row is no longer a candidate.
 */
@DataJpaTest
class ChangeLogDdlHashBackfillTest {

    private static final String DDL = "CDC_SKIPPED_DDL:ALTER TABLE t ADD COLUMN c INT";

    @Autowired
    private ChangeLogRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void legacyDdlTaskRowsGetTheHashOfTheirStatement() {
        ChangeLog open = row(ChangeLog.RESOLUTION_PENDING, null);
        ChangeLog processed = row(ChangeLog.RESOLUTION_PROCESSED, null);
        // Ordinary history rows are not tasks and must not be touched.
        ChangeLog ordinaryFailure = row(null, null);

        ChangeLogDdlHashBackfill backfill =
                new ChangeLogDdlHashBackfill(repository, transactionManager);

        assertThat(backfill.backfill()).isEqualTo(2);
        assertThat(reloaded(open).getDetailsHash()).isEqualTo(DigestUtil.sha256Hex(DDL));
        assertThat(reloaded(processed).getDetailsHash()).isEqualTo(DigestUtil.sha256Hex(DDL));
        assertThat(reloaded(ordinaryFailure).getDetailsHash()).isNull();

        assertThat(backfill.backfill()).as("幂等：第二次启动不再更新任何行").isZero();
    }

    @Test
    void anAlreadyHashedRowIsLeftAlone() {
        ChangeLog hashed = row(ChangeLog.RESOLUTION_PENDING, DigestUtil.sha256Hex(DDL));

        assertThat(new ChangeLogDdlHashBackfill(repository, transactionManager).backfill())
                .isZero();
        assertThat(reloaded(hashed).getDetailsHash()).isEqualTo(DigestUtil.sha256Hex(DDL));
    }

    private ChangeLog reloaded(ChangeLog row) {
        return repository.findById(row.getId()).orElseThrow();
    }

    private ChangeLog row(String status, String detailsHash) {
        ChangeLog log = new ChangeLog();
        log.setProjectId(1L);
        log.setObjectType(ObjectType.PROJECT);
        log.setObjectName("t");
        log.setChangeType(ChangeType.ERROR);
        log.setSuccess(false);
        log.setDetails(DDL);
        log.setDetailsHash(detailsHash);
        log.setResolutionStatus(status);
        log.setOccurredAt(Instant.parse("2026-01-01T00:00:00Z"));
        return repository.saveAndFlush(log);
    }
}
