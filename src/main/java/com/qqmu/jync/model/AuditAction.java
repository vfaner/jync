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
    /** A database connection was created or updated. */
    DB_SAVE,
    /** A database connection was deleted. */
    DB_DELETE,
    /** Change-log history was cleared. */
    CHANGELOG_CLEAR
}
