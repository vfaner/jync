package com.synctool.model;

/**
 * Classifies a {@link DatabaseConfig} as a data source (read side) or a data target (write side).
 * Every connection picks exactly one role; the classification drives the two sections on the
 * connections page and filters the source/target selectors of the project form.
 */
public enum ConnectionRole {
    SOURCE,
    TARGET
}
