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
    /** A diagnostics bundle was exported, including logs and configuration shape. */
    DIAGNOSTICS_EXPORT,
    /** A Redis cache target was created or updated. */
    REDIS_TARGET_SAVE,
    /** A Redis cache target was deleted. */
    REDIS_TARGET_DELETE,
    /** A Redis project's cache was rebuilt by hand: prefix swept, stream re-anchored, baseline rerun. */
    REDIS_REBUILD,
    /** A dedicated-migration connection was created or updated. */
    MIGRATION_CONNECTION_SAVE,
    /** A dedicated-migration connection was deleted. */
    MIGRATION_CONNECTION_DELETE,
    /** A dedicated-migration task was created or updated. */
    MIGRATION_TASK_SAVE,
    /** A dedicated-migration task was deleted. */
    MIGRATION_TASK_DELETE,
    /** A system setting was changed from the 系统配置 page (currently the HTTPS switch). */
    SYSTEM_SETTING_SAVE,
    /** A configuration package was exported. */
    CONFIG_EXPORT,
    /** A configuration package was imported into this (empty) metadata database. */
    CONFIG_IMPORT,
    /** A TLS certificate was uploaded on the 系统配置 page (applies after a restart). */
    TLS_CONFIG_SAVE,
    /** The saved TLS certificate was removed on the 系统配置 page (applies after a restart). */
    TLS_CONFIG_DELETE,
    /**
     * Legacy: the 系统配置 metadata-database connection tester wrote this before it was
     * removed. Kept so the append-only rows still load ({@code @Enumerated(STRING)} is
     * strict); nothing records it any more.
     */
    SYSTEM_DB_TEST
}
