package com.qqmu.jync.service.auth;

import org.springframework.stereotype.Component;

/**
 * Throttles failed ticket validations at the login centre's return address.
 *
 * <p>{@code GET /sso/entry} is reachable without a session, and every call it accepts makes one
 * outbound request to the portal and writes one audit row. The password paths are already guarded
 * by {@link LoginAttemptService}; without this the ticket path would be the one open door an
 * anonymous caller could use to keep the portal — and the request threads waiting on it — busy.
 *
 * <p>Keyed by client address only, because there is no account name to pair it with, and the
 * threshold is looser than a password's on purpose: people behind one NAT share an address, and a
 * mistimed click on the portal's back button is an ordinary way to present an expired ticket.
 */
@Component
public class TicketAttemptLimiter {

    /** Bad tickets tolerated from one address before the entry point stops calling the portal. */
    public static final int MAX_ATTEMPTS = 20;

    /** How long the address stays blocked once the threshold is hit. */
    public static final long LOCK_MS = LoginAttemptService.LOCK_MS;

    /**
     * Prefix that keeps this counter's keys out of the password keys' namespace. {@code key()}
     * uppercases its first part, so the result reads {@code SSO|<ip>}.
     */
    private static final String KEY_NAMESPACE = "sso";

    private final LoginAttemptService attempts = new LoginAttemptService(MAX_ATTEMPTS, LOCK_MS);

    public boolean isBlocked(String clientIp) {
        return attempts.isBlocked(key(clientIp));
    }

    public void recordFailure(String clientIp) {
        attempts.recordFailure(key(clientIp));
    }

    public void recordSuccess(String clientIp) {
        attempts.recordSuccess(key(clientIp));
    }

    private static String key(String clientIp) {
        return LoginAttemptService.key(KEY_NAMESPACE, clientIp);
    }
}
