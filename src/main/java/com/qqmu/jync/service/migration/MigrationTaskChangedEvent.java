package com.qqmu.jync.service.migration;

/** Published after a migration task is saved or deleted so the scheduler can converge. */
public record MigrationTaskChangedEvent(Long taskId, boolean deleted) {
}
