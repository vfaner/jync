package com.qqmu.jync.service.auth;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;

/** The administrator rules shared by every external-auth mode. */
public final class ExternalUserRoles {

    private ExternalUserRoles() {
    }

    /**
     * {@code adminUsers} is a comma-separated list of exact (trimmed, case-sensitive) user names.
     * A mode-specific marker (a response field or an admin header) can grant the role too.
     */
    public static UserRole resolve(OaAuthConfig config, String username, boolean responseAdmin) {
        return matchesUsername(config.getAdminUsers(), username) || responseAdmin
                ? UserRole.ADMIN : UserRole.VIEWER;
    }

    public static boolean matchesUsername(String commaSeparated, String username) {
        if (commaSeparated == null || username == null) {
            return false;
        }
        for (String part : commaSeparated.split(",")) {
            if (part.trim().equals(username)) {
                return true;
            }
        }
        return false;
    }
}
