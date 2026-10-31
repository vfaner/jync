package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.EtlRun;

public interface EtlRunRepository extends JpaRepository<EtlRun, Long> {

    /**
     * The dashboard's ETL line: every run inside the chart's 24-hour window, bucketed by the
     * caller. Unlike the change log this is a handful of rows — a run per schedule, not per
     * changed row — so no database-side aggregation is needed.
     */
    List<EtlRun> findByOccurredAtBetweenOrderByOccurredAtAsc(Instant from, Instant to);
}
