package com.qqmu.jync.service.etl;

/**
 * Published after an ETL task is created, edited, enabled, disabled or deleted, so the
 * scheduler can converge its cron registration without the task service depending on it.
 */
public record EtlTaskChangedEvent(Long taskId, boolean deleted) {
}
