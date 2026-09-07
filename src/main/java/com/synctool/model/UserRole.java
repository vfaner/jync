package com.synctool.model;

/**
 * What a signed-in user is allowed to do.
 *
 * <p>Only two levels, deliberately. Every state change in this application is a POST — audited
 * endpoint by endpoint, no GET mutates anything — so "may write" is a single, checkable bit
 * rather than a matrix of per-resource permissions that would drift from the controllers.
 */
public enum UserRole {

    /** Read-only: may view every page, may change nothing but their own password. */
    VIEWER,

    /** Full access, including the operations that reach outside this process. */
    ADMIN;

    /** Spring Security expects the {@code ROLE_} prefix on authorities. */
    public String authority() {
        return "ROLE_" + name();
    }
}
