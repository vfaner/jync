package com.qqmu.jync.service.auth.oa;

/** Outcome of one call to the enterprise OA's sign-in API. */
public final class OaAuthResult {

    private final boolean success;
    private final String username;
    private final boolean admin;
    private final String errorKey;

    private OaAuthResult(boolean success, String username, boolean admin, String errorKey) {
        this.success = success;
        this.username = username;
        this.admin = admin;
        this.errorKey = errorKey;
    }

    public static OaAuthResult success(String username, boolean admin) {
        return new OaAuthResult(true, username, admin, null);
    }

    /** {@code errorKey} is an i18n message key shown on the login page. */
    public static OaAuthResult failure(String errorKey) {
        return new OaAuthResult(false, null, false, errorKey);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getUsername() {
        return username;
    }

    public boolean isAdmin() {
        return admin;
    }

    public String getErrorKey() {
        return errorKey;
    }
}
