package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.MetadataSnapshot;
import com.qqmu.jync.model.ObjectType;

public interface MetadataSnapshotRepository extends JpaRepository<MetadataSnapshot, Long> {

    Optional<MetadataSnapshot> findByProjectIdAndObjectTypeAndObjectName(
            Long projectId, ObjectType objectType, String objectName);

    /**
     * Names and content hashes of a project's snapshots — deliberately not the JSON.
     *
     * <p>Pulling every {@code @Lob} blob just to learn that nothing changed was the
     * dominant cost of a poll cycle. Rows whose hash the service already caches need no
     * further read; the remainder are fetched one by one through
     * {@link #findByProjectIdAndObjectTypeAndObjectName}.
     */
    interface NameAndHash {
        String getObjectName();

        String getContentHash();
    }

    @Query("select s.objectName as objectName, s.contentHash as contentHash "
            + "from MetadataSnapshot s where s.projectId = :projectId and s.objectType = :type")
    List<NameAndHash> findNameAndHashByProjectIdAndObjectType(
            @Param("projectId") Long projectId, @Param("type") ObjectType type);

    // Bulk deletes: a derived deleteByProjectId first SELECTs and loads every entity — including
    // the @Lob JSON snapshot — just to remove it. The JPQL deletes remove the rows directly.
    @Modifying
    @Query("delete from MetadataSnapshot s where s.projectId = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from MetadataSnapshot s where s.projectId = :projectId "
            + "and s.objectType = :objectType and s.objectName = :objectName")
    void deleteByProjectIdAndObjectTypeAndObjectName(@Param("projectId") Long projectId,
                                                     @Param("objectType") ObjectType objectType,
                                                     @Param("objectName") String objectName);
}
