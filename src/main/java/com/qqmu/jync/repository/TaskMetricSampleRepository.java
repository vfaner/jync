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

    /**
     * The newest row of every project in one query. The Prometheus scrape runs on a short
     * interval, so a lookup per project would multiply by the size of the project list.
     */
    @Query("select s from TaskMetricSample s where s.id in "
            + "(select max(m.id) from TaskMetricSample m group by m.projectId)")
    List<TaskMetricSample> findLatestPerProject();

    /** Bulk, not a derived delete: a derived delete loads every row before removing it. */
    @Modifying
    @Query("delete from TaskMetricSample s where s.projectId = :id")
    int deleteByProjectId(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("delete from TaskMetricSample s where s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
