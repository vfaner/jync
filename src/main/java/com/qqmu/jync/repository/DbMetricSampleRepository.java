package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.DbMetricSample;

public interface DbMetricSampleRepository extends JpaRepository<DbMetricSample, Long> {

    List<DbMetricSample> findByConnectionIdAndSampledAtAfterOrderBySampledAtAsc(
            Long connectionId, Instant after);

    /** The latest probe decides the status badge and whether the page shows its red banner. */
    Optional<DbMetricSample> findTopByConnectionIdOrderBySampledAtDesc(Long connectionId);

    /**
     * The newest row of every connection in one query. The Prometheus scrape runs on a
     * short interval, so a lookup per connection would multiply by the size of the list.
     */
    @Query("select s from DbMetricSample s where s.id in "
            + "(select max(m.id) from DbMetricSample m group by m.connectionId)")
    List<DbMetricSample> findLatestPerConnection();

    /** Bulk, not a derived delete: a derived delete loads every row before removing it. */
    @Modifying
    @Query("delete from DbMetricSample s where s.connectionId = :id")
    int deleteByConnectionId(@Param("id") Long id);

    /**
     * Retention pruning. The transaction lives here so the pruner stays outside one and can
     * swallow a failure without tripping over a rollback-only mark.
     */
    @Modifying
    @Transactional
    @Query("delete from DbMetricSample s where s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
