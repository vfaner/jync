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

    /**
     * Usernames longer than this are truncated before they become a key.
     *
     * <p>The sign-in form caps the field at 64 characters, but that cap lives in the browser.
     * The HTTP body can be up to Tomcat's form-post limit (~2 MB), and an entry keyed by a
     * 2 MB username is retained for as long as the process lives — a handful of unauthenticated
     * requests would otherwise pin hundreds of megabytes. No real account name is this long.
     */
    public static final int MAX_USERNAME_LENGTH = 64;

    /**
     * Upper bound on tracked pairs.
     *
     * <p>Every failed sign-in with a previously unseen username creates an entry, and an
     * unauthenticated caller controls the username. Without a bound the map is an unbounded
     * memory sink; entries are tiny (a truncated key plus a counter), so 10 000 of them cost
     * a couple of megabytes. Once the cap is reached, expired entries are reclaimed first and
     * then arbitrary ones — a flooded map protects the specific account being guessed no worse
     * than the attacker's own rotating usernames already do.
     */
    public static final int MAX_TRACKED_KEYS = 10_000;

    private record Attempts(int count, long windowStart) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private final LongSupplier clock;
    private final int maxAttempts;
    private final long lockMs;

    public LoginAttemptService() {
        this(MAX_ATTEMPTS, LOCK_MS, System::currentTimeMillis);
    }

    /**
     * A separate limiter for a surface with a different tolerance — the ticket entry point, where
     * several people can legitimately share one address behind NAT and each bad ticket costs an
     * outbound validation call.
     */
    public LoginAttemptService(int maxAttempts, long lockMs) {
        this(maxAttempts, lockMs, System::currentTimeMillis);
    }

    /** Test constructor with an injectable clock. */
    LoginAttemptService(LongSupplier clock) {
        this(MAX_ATTEMPTS, LOCK_MS, clock);
    }

    LoginAttemptService(int maxAttempts, long lockMs, LongSupplier clock) {
        this.maxAttempts = maxAttempts;
        this.lockMs = lockMs;
        this.clock = clock;
    }

    /** True while the pair is inside an active lockout window. Expired windows are cleared. */
    public boolean isBlocked(String key) {
        Attempts a = attempts.get(key);
        if (a == null || a.count < maxAttempts) {
            return false;
        }
        if (clock.getAsLong() - a.windowStart >= lockMs) {
            attempts.remove(key);
            return false;
        }
        return true;
    }

    /** Registers one failed password, starting a new window after the previous one expires. */
    public void recordFailure(String key) {
        attempts.compute(key, (k, previous) -> {
            long now = clock.getAsLong();
            if (previous == null || now - previous.windowStart >= lockMs) {
                return new Attempts(1, now);
            }
            return new Attempts(previous.count + 1, previous.windowStart);
        });
        if (attempts.size() > MAX_TRACKED_KEYS) {
            prune();
        }
    }

    /** Clears the counter after a successful sign-in. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /** Number of tracked pairs; package-private so the cap can be asserted directly. */
    int trackedKeys() {
        return attempts.size();
    }

    /**
     * Reclaims expired windows, then drops arbitrary entries if the map is still over the cap.
     * Called only when the cap is exceeded, so the steady state costs one size check per failure.
     */
    private void prune() {
        long now = clock.getAsLong();
        attempts.entrySet().removeIf(e -> now - e.getValue().windowStart() >= lockMs);
        var it = attempts.keySet().iterator();
        while (attempts.size() > MAX_TRACKED_KEYS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /**
     * Lockout key: username (case-insensitive, length-capped) plus the client IP.
     *
     * <p>The username is trimmed and truncated <em>before</em> the upper-casing so an oversized
     * body is never copied whole into a longer-lived string.
     */
    public static String key(String username, String clientIp) {
        if (username == null) {
            return "|" + clientIp;
        }
        String trimmed = username.trim();
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_USERNAME_LENGTH);
        }
        return trimmed.toUpperCase() + "|" + clientIp;
    }
}
