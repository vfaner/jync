package com.qqmu.jync.service.migration;

/** What a run does when the target object already exists. */
public enum MigrationWriteMode {
    OVERWRITE,
    APPEND,
    FAIL_IF_EXISTS
}
