package com.qqmu.jync.model;

/**
 * The kinds of events the append-only admin audit trail records.
 *
 * <p>Kept small on purpose: sign-ins, destructive or hard-to-attribute mutations, and the
 * clearing of the change log (which otherwise erases its own accountability).
 */
public enum AuditAction {
    /** A form login that succeeded. */
    LOGIN,
    /** A form login that was rejected (bad credentials, unknown user). */
    LOGIN_FAILED,
    /** A project was created or updated. */
    PROJECT_SAVE,
    /** A project and all of its sync state were deleted. */
    PROJECT_DELETE,
    /** The sync lease was force-released by hand. */
    PROJECT_FORCE_UNLOCK,
    /** A project's capture strategy was switched (v1.5.0 C2, e.g. POLLING → BINLOG_CDC). */
    PROJECT_CAPTURE_SWITCH,
    /** A CDC project's baseline was rebuilt by hand (v1.5.0 B7 one-click recovery). */
    PROJECT_CDC_REBASELINE,
    /** A database connection was created or updated. */
    DB_SAVE,
    /** A database connection was deleted. */
    DB_DELETE,
    /** Change-log history was cleared. */
    CHANGELOG_CLEAR,
    /** A dedicated-migration connection was created or updated. */
    MIGRATION_CONNECTION_SAVE,
    /** A dedicated-migration connection was deleted. */
    MIGRATION_CONNECTION_DELETE,
    /** A dedicated-migration task was created or updated. */
    MIGRATION_TASK_SAVE,
    /** A dedicated-migration task was deleted. */
    MIGRATION_TASK_DELETE
}
