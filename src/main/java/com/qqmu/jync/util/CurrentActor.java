package com.qqmu.jync.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** One actor spelling for audit and change-log rows written outside a login flow. */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** The signed-in username, or "system" for startup/background work with no request. */
    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // AnonymousAuthenticationToken reports authenticated; its name is "anonymousUser".
        return auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication
                        .AnonymousAuthenticationToken
                ? "system" : auth.getName();
    }
}
