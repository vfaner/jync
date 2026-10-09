package com.qqmu.jync.service.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts consecutive failed sign-ins and enforces a temporary lockout.
 *
 * <p>Before this, the login endpoint answered unlimited guesses forever — with two seeded
 * accounts and a documented default password, an unprotected deployment was a password
 * spray away from an ADMIN session. After {@link #MAX_ATTEMPTS} failures the account+IP
 * pair is refused for {@link #LOCK_MS}, after which the counter expires and a fresh run of
 * failures is possible (a permanent lock would be a trivial way to lock the real admin out).
 *
 * <p>The key includes the client IP, so an attacker brute-forcing from one network does not
 * lock the legitimate user working from another — and an attacker rotating IPs gets only
 * {@code MAX_ATTEMPTS} guesses per IP rather than unlimited.
 */
public class LoginAttemptService {

    /** Failures tolerated before the lockout. */
    public static final int MAX_ATTEMPTS = 5;

    /** How long the pair stays blocked once the threshold is hit. */
    public static final long LOCK_MS = 15 * 60 * 1000L;

    private record Attempts(int count, long windowStart) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public LoginAttemptService() {
        this(System::currentTimeMillis);
    }

    /** Test constructor with an injectable clock. */
    LoginAttemptService(LongSupplier clock) {
        this.clock = clock;
    }

    /** True while the pair is inside an active lockout window. Expired windows are cleared. */
    public boolean isBlocked(String key) {
        Attempts a = attempts.get(key);
        if (a == null || a.count < MAX_ATTEMPTS) {
            return false;
        }
        if (clock.getAsLong() - a.windowStart >= LOCK_MS) {
            attempts.remove(key);
            return false;
        }
        return true;
    }

    /** Registers one failed password, starting a new window after the previous one expires. */
    public void recordFailure(String key) {
        attempts.compute(key, (k, previous) -> {
            long now = clock.getAsLong();
            if (previous == null || now - previous.windowStart >= LOCK_MS) {
                return new Attempts(1, now);
            }
            return new Attempts(previous.count + 1, previous.windowStart);
        });
    }

    /** Clears the counter after a successful sign-in. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /** Lockout key: username (case-insensitive) plus the client IP. */
    public static String key(String username, String clientIp) {
        return (username == null ? "" : username.trim().toUpperCase()) + "|" + clientIp;
    }
}
