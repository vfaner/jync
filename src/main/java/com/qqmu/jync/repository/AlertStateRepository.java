package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AlertCategory;
import com.qqmu.jync.model.AlertState;

/**
 * One row per (category, project, object), and the cross-node claim every announcement goes
 * through.
 *
 * <p>The claim methods are conditional row updates, not read-then-save: two nodes evaluating in
 * the same minute both read the row, and only the {@code UPDATE ... WHERE} that the database
 * serializes can tell them apart. Each returns 1 for the caller that won and 0 for the others.
 */
public interface AlertStateRepository extends JpaRepository<AlertState, Long> {

    Optional<AlertState> findByCategoryAndProjectIdAndObjectKey(AlertCategory category,
                                                                Long projectId,
                                                                String objectKey);

    /** The live board: every condition still standing, newest first. Cleared rows are pruned. */
    List<AlertState> findByActiveTrueOrderByFirstSeenAtDesc();

    /**
     * Removes every alert state owned by a project. Connection-wide rows store {@code 0}, so
     * a positive project id never touches them.
     */
    void deleteByProjectId(Long projectId);

    /**
     * Claims the first announcement of a fault by flipping the row from inactive to active with
     * the timestamps this node will report.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AlertState s set s.active = true, s.firstSeenAt = :firstSeenAt, "
            + "s.subject = :subject, s.lastNotifiedAt = :notifiedAt, "
            + "s.lastEventAt = :lastEventAt "
            + "where s.id = :id and s.active = false")
    int claimStart(@Param("id") Long id,
                   @Param("firstSeenAt") Instant firstSeenAt,
                   @Param("subject") String subject,
                   @Param("notifiedAt") Instant notifiedAt,
                   @Param("lastEventAt") Instant lastEventAt);

    /**
     * Claims the next repeat of a condition: only a row whose last announcement predates
     * {@code cutoff} (the cooldown window) can be taken.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AlertState s set s.lastNotifiedAt = :notifiedAt "
            + "where s.id = :id and s.active = true "
            + "and (s.lastNotifiedAt is null or s.lastNotifiedAt < :cutoff)")
    int claimRepeat(@Param("id") Long id,
                    @Param("notifiedAt") Instant notifiedAt,
                    @Param("cutoff") Instant cutoff);

    /** Claims an event newer than the one already announced; an older or equal one is not news. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AlertState s set s.lastNotifiedAt = :notifiedAt, s.lastEventAt = :eventAt "
            + "where s.id = :id and s.active = true "
            + "and (s.lastEventAt is null or s.lastEventAt < :eventAt)")
    int claimEvent(@Param("id") Long id,
                   @Param("notifiedAt") Instant notifiedAt,
                   @Param("eventAt") Instant eventAt);

    /**
     * Claims the recovery: only the node that flips an active row to inactive sends it, so a
     * cleared fault produces one recovery message, not one per node.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AlertState s set s.active = false where s.id = :id and s.active = true")
    int claimClearance(@Param("id") Long id);

    /**
     * Drops rows whose condition has cleared. A later fault of the same kind writes its own
     * timestamps, so an inactive row carries no memory worth keeping — and every table that
     * ever drifted would otherwise leave one behind forever.
     *
     * <p>A derived {@code deleteByActiveFalse()} would select the rows and then delete them one
     * by one. Between those statements another node can claim a selected row with
     * {@link #claimStart}, and the per-id delete would remove the row it just activated —
     * re-announcing the fault and resetting its cooldown. One conditional statement has no such
     * gap: an update that wins the race leaves the row active, and one that loses finds nothing
     * to claim.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AlertState s where s.active = false")
    int deleteInactive();
}
