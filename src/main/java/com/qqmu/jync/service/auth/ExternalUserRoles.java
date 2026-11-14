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
        // One implementation with the response-field comparison: the two used to trim on
        // opposite sides, which is invisible today and a role bug the day it is not.
        return ExternalIdentity.matches(username, commaSeparated);
    }
}
