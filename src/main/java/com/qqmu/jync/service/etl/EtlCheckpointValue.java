package com.qqmu.jync.service.etl;

/**
 * The last committed resume-key value of an ETL run.
 *
 * @param jdbcType the {@link java.sql.Types} constant reported for the key column, used to
 *                 bind it back into the continuation query with the right Java type
 * @param value    the value rendered as text; see {@link EtlRunner} for the supported types
 */
public record EtlCheckpointValue(int jdbcType, String value) {
}
