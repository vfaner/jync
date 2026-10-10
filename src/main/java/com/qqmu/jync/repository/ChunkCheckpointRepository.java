package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChunkCheckpoint;

public interface ChunkCheckpointRepository extends JpaRepository<ChunkCheckpoint, Long> {

    /**
     * Every resume point of one table's in-flight load, across all ranges; the caller picks
     * each range's newest row (max {@code chunkIndex}) to decide where — or whether — to
     * continue.
     */
    List<ChunkCheckpoint> findByProjectIdAndTableName(Long projectId, String tableName);

    /** Every in-flight load of the project, for the detail page's progress panel. */
    List<ChunkCheckpoint> findByProjectId(Long projectId);

    @Transactional
    void deleteByProjectIdAndTableName(Long projectId, String tableName);
}
