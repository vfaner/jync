package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.dto.ProgressStatusRow;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.SyncProgress;

public interface SyncProgressRepository extends JpaRepository<SyncProgress, Long> {

    Optional<SyncProgress> findByProjectIdAndObjectTypeAndObjectName(
            Long projectId, ObjectType objectType, String objectName);

    List<SyncProgress> findByProjectId(Long projectId);

    /**
     * Columns needed by the detail page's per-table status; never loads the cursor LOB.
     * The status endpoint is polled every five seconds, so a full-entity read for hundreds
     * of tables was pure metadata-store I/O.
     */
    @Query("select new com.qqmu.jync.dto.ProgressStatusRow(p.objectName, p.initialLoadDone, "
            + "p.rowsSyncedTotal, p.lastSyncTime, p.cursorStrategy, p.cursorColumn, "
            + "p.resumableLoad, p.fullLoadWithoutCursor, p.fullLoadTotalRows, "
            + "p.fullLoadCompletedAt, case when p.lastSyncValue is null then false else true end) "
            + "from SyncProgress p "
            + "where p.projectId = :projectId and p.objectType = :objectType")
    List<ProgressStatusRow> findStatusRowsByProjectId(@Param("projectId") Long projectId,
                                                      @Param("objectType") ObjectType objectType);

    /**
     * Delivered rows for one project, summed in the database. Loading the rows instead pulled
     * every object's {@code last_sync_value} LOB (it is a @Lob column) once a minute per
     * project, only to add up one numeric column.
     */
    @Query("select coalesce(sum(p.rowsSyncedTotal), 0) from SyncProgress p "
            + "where p.projectId = :projectId")
    long sumRowsSyncedByProject(@Param("projectId") Long projectId);

    /** Tracked-object counts for every project in one query, instead of one query per project. */
    @Query("select p.projectId, count(p) from SyncProgress p "
            + "where p.objectType = :objectType group by p.projectId")
    List<Object[]> countByObjectTypeGroupedByProject(@Param("objectType") ObjectType objectType);

    /** Progress rows for one CDC change-window flush, loaded in one transaction for update. */
    @Query("select p from SyncProgress p where p.projectId = :projectId "
            + "and p.objectType = :objectType and p.objectName in :names")
    List<SyncProgress> findCdcProgressRows(@Param("projectId") Long projectId,
                                           @Param("objectType") ObjectType objectType,
                                           @Param("names") java.util.Collection<String> names);

    // Bulk, not a derived delete: a derived delete loads each entity before removing it.
    @Modifying
    @Query("delete from SyncProgress p where p.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
