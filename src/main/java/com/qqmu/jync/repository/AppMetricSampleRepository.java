package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.AppMetricSample;

public interface AppMetricSampleRepository extends JpaRepository<AppMetricSample, Long> {

    List<AppMetricSample> findBySampledAtAfterOrderBySampledAtAsc(Instant after);

    /** The newest process sample, for the Prometheus scrape. */
    Optional<AppMetricSample> findTopByOrderBySampledAtDesc();

    /** Process samples belong to the JVM, not to a project, so only retention removes them. */
    @Modifying
    @Transactional
    @Query("delete from AppMetricSample s where s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
