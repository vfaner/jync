package com.qqmu.jync.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.ChunkCheckpoint;

public interface ChunkCheckpointRepository extends JpaRepository<ChunkCheckpoint, Long> {

    /** Descending: the first row is the newest completed chunk, i.e. the resume point. */
    List<ChunkCheckpoint> findByProjectIdAndTableNameOrderByChunkIndexDesc(
            Long projectId, String tableName);

    @Transactional
    void deleteByProjectIdAndTableName(Long projectId, String tableName);
}
