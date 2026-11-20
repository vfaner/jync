package com.qqmu.jync.service.ai.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Single-use confirmation tokens for assistant-prepared actions.
 *
 * <p>The assistant never executes a write. A control tool prepares the action — re-applying
 * the same ownership rule the buttons use — and stores it here with a random token. Only the
 * user who asked can redeem it, only once, and only within five minutes. Execution re-checks
 * everything through the ordinary service methods, so this store is a hand-off, not an
 * authority.
 *
 * <p>Memory only: like drafts, a pending action is a paused suggestion, and a restart simply
 * means asking again. A pending action that is never used is swept on the next put.
 */
@Component
public class SupportActionStore {

    public enum Kind {
        PROJECT, ETL_TASK
    }

    public enum Action {
        START, STOP, SYNC_NOW, RUN_NOW,
        /** CDC recovery: reset the position and let the next start run a full load. */
        REBASELINE
    }

    public record Pending(String username, Kind kind, Long resourceId, Action action,
                          String resourceName, Instant expiresAt) {
    }

    static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String put(String username, Kind kind, Long resourceId, Action action,
                      String resourceName) {
        Instant now = Instant.now();
        pending.values().removeIf(item -> item.expiresAt().isBefore(now));

        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        pending.put(token, new Pending(username, kind, resourceId, action, resourceName,
                now.plusMillis(TTL_MILLIS)));
        return token;
    }

    /** Removes and returns the action; an unknown, foreign or expired token yields empty. */
    public Optional<Pending> take(String token, String username) {
        Pending item = token == null ? null : pending.get(token);
        if (item == null || username == null || !item.username().equals(username)
                || item.expiresAt().isBefore(Instant.now())) {
            // Only a successful take burns the token: a shared link opened by the wrong
            // account, or an expired one, must not consume the owner's confirmation.
            return Optional.empty();
        }
        return Optional.ofNullable(pending.remove(token));
    }

    /** The card title key for one kind and action, shared by the tools and the auditor. */
    public static String titleKey(Kind kind, Action action) {
        return "support.action." + kind.name().toLowerCase(Locale.ROOT)
                + "." + action.name().toLowerCase(Locale.ROOT);
    }

    /** Test seam: how many tokens are still held (expired ones included until a put). */
    int sizeForTests() {
        return pending.size();
    }

    /** Test seam: move one token past its expiry without sleeping. */
    void expireForTests(String token) {
        Pending item = pending.get(token);
        if (item != null) {
            pending.put(token, new Pending(item.username(), item.kind(), item.resourceId(),
                    item.action(), item.resourceName(), Instant.now().minusSeconds(1)));
        }
    }
}
