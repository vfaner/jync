package com.qqmu.jync.service.etl;

/**
 * Durable resume position of an ETL task. Kept behind an interface so the copy loop can be
 * tested without a database, and so the JPA implementation stays out of the runner.
 */
public interface EtlCheckpointStore {

    /** The stored position, or null when the task has none. */
    EtlCheckpointValue load(Long taskId);

    /** Records the position after a batch has been committed to the target. */
    void save(Long taskId, int jdbcType, String value);

    /** Forgets the position; called when configurations change or an overwrite starts. */
    void clear(Long taskId);
}
