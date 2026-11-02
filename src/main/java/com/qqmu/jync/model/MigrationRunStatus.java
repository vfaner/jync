package com.qqmu.jync.model;

/** Terminal state of one dedicated-migration run. */
public enum MigrationRunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    /** The process stopped while the run was in flight; startup recovery marked it. */
    INTERRUPTED
}
