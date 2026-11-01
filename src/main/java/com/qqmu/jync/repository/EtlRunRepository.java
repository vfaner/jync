package com.qqmu.jync.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.qqmu.jync.model.EtlRun;

public interface EtlRunRepository extends JpaRepository<EtlRun, Long> {

    /**
     * The dashboard's ETL line: every run inside the chart's 24-hour window, bucketed by the
     * caller. Unlike the change log this is a handful of rows — a run per schedule, not per
     * changed row — so no database-side aggregation is needed.
     */
    List<EtlRun> findByOccurredAtBetweenOrderByOccurredAtAsc(Instant from, Instant to);

    /**
     * The 24-hour tiles: how many runs happened and what they wrote. The list above would
     * answer the same question by loading every run entity into memory, once per ten-second
     * refresh of the dashboard.
     */
    long countByOccurredAtBetween(Instant from, Instant to);

    @Query("select coalesce(sum(r.affectedRows), 0) from EtlRun r "
            + "where r.occurredAt >= :from and r.occurredAt <= :to")
    long sumAffectedRowsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
