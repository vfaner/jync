package com.qqmu.jync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.CdcPosition;

public interface CdcPositionRepository extends JpaRepository<CdcPosition, Long> {

    Optional<CdcPosition> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from CdcPosition p where p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    java.util.List<CdcPosition> findByExternalBaselineVerifyStatusAndExternalBaselineVerifiedAtBefore(
            String status, java.time.Instant cutoff);

    /**
     * Verification metadata is diagnostic and must not rewrite the position row through a
     * stale entity: a live consumer may advance the row between the read and the save. A
     * bulk update touches only these columns and cannot lose that advance to optimistic
     * locking.
     */
    @Modifying
    @Query("update CdcPosition p set p.externalBaselineVerifyStatus = :status,"
            + " p.externalBaselineVerifyIssues = :issues,"
            + " p.externalBaselineVerifyDetails = :details,"
            + " p.externalBaselineVerifiedAt = :verifiedAt"
            + " where p.projectId = :projectId")
    int markExternalBaselineVerification(@Param("projectId") Long projectId,
                                         @Param("status") String status,
                                         @Param("issues") Integer issues,
                                         @Param("details") String details,
                                         @Param("verifiedAt") java.time.Instant verifiedAt);
}
