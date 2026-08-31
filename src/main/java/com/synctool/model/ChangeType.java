package com.synctool.model;

/** The nature of a detected or applied change. */
public enum ChangeType {
    CREATE,
    ALTER,
    DROP,
    INSERT,
    UPDATE,
    DELETE,
    /** No structural or row change, but the sync run itself is worth recording. */
    INFO,
    ERROR
}
