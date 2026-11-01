package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.TaskMetricSample;

public interface TaskMetricSampleRepository extends JpaRepository<TaskMetricSample, Long> {

    List<TaskMetricSample> findByProjectIdAndSampledAtAfterOrderBySampledAtAsc(
            Long projectId, Instant after);

    /** The previous sample, so the sampler can turn a running total into a per-minute delta. */
    Optional<TaskMetricSample> findTopByProjectIdOrderBySampledAtDesc(Long projectId);

    /** Bulk, not a derived delete: a derived delete loads every row before removing it. */
    @Modifying
    @Query("delete from TaskMetricSample s where s.projectId = :id")
    int deleteByProjectId(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("delete from TaskMetricSample s where s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
