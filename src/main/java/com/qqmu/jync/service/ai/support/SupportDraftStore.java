package com.qqmu.jync.service.ai.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.Project;

/**
 * Single-use draft tokens.
 *
 * <p>Memory only: a draft is a suggestion paused for human review, not data. Five minutes and one
 * use is enough to open the prefilled form; a restart or a refresh simply means asking again.
 */
@Component
public class SupportDraftStore {

    public record Draft(String username, Project project, String suggestion, Instant expiresAt) {
    }

    static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String put(String username, Project project, String suggestion) {
        // An unused draft is never removed by any other path, so sweep the expired ones on
        // every put; the map only ever holds drafts from the last five minutes.
        Instant now = Instant.now();
        drafts.values().removeIf(draft -> draft.expiresAt().isBefore(now));

        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        drafts.put(token, new Draft(username, project, suggestion, now.plusMillis(TTL_MILLIS)));
        return token;
    }

    /** Removes and returns the draft; an unknown, foreign or expired token yields empty. */
    public Optional<Draft> take(String token, String username) {
        Draft draft = token == null ? null : drafts.get(token);
        if (draft == null || !draft.username().equals(username)
                || draft.expiresAt().isBefore(Instant.now())) {
            // Only a successful take burns the token: a shared link opened by the wrong
            // account, or an expired one, must not consume the owner's draft.
            return Optional.empty();
        }
        return Optional.ofNullable(drafts.remove(token));
    }

    /** Test seam: how many tokens are still held (expired ones included until a put). */
    int sizeForTests() {
        return drafts.size();
    }

    /** Test seam: move one token past its expiry without sleeping. */
    void expireForTests(String token) {
        Draft draft = drafts.get(token);
        if (draft != null) {
            drafts.put(token, new Draft(draft.username(), draft.project(), draft.suggestion(),
                    Instant.now().minusSeconds(1)));
        }
    }
}
