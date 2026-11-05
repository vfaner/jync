package com.qqmu.jync.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fills {@code project.target_kind} on rows written before Redis targets existed.
 *
 * <p>{@code ddl-auto: update} adds the column as nullable, so every legacy row reads back as
 * null. The application treats null as JDBC on its own — this backfill only makes the stored
 * rows agree with that reading, so an operator querying the table sees the same answer the
 * application does. It is idempotent and runs on every start; the second run updates nothing.
 *
 * <p>A failure is logged, not rethrown: the column is nullable and every reader defaults null
 * to JDBC, so a missing backfill is cosmetic rather than a reason to refuse to boot.
 */
@Component
@Slf4j
public class ProjectTargetKindBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ProjectTargetKindBackfill(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE project SET target_kind = 'JDBC' WHERE target_kind IS NULL");
            if (updated > 0) {
                log.info("Backfilled target_kind=JDBC on {} legacy project row(s)", updated);
            }
        } catch (RuntimeException e) {
            log.warn("Could not backfill project.target_kind; legacy rows still read as JDBC: {}",
                    e.getMessage());
        }
    }
}
