package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * P1: lockout bookkeeping — threshold, expiry, reset, and key normalization.
 */
class LoginAttemptServiceTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final LoginAttemptService service = new LoginAttemptService(now::get);
    private final String key = LoginAttemptService.key("admin", "10.0.0.1");

    @Test
    void staysOpenBelowTheThreshold() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure(key);
            assertThat(service.isBlocked(key)).isFalse();
        }
    }

    @Test
    void locksOnTheLastAttemptOfTheThreshold() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure(key);
        }
        assertThat(service.isBlocked(key)).isTrue();
    }

    @Test
    void aSuccessfulLoginClearsTheCounter() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure(key);
        }
        service.recordSuccess(key);

        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure(key);
        }
        assertThat(service.isBlocked(key)).isFalse();
    }

    @Test
    void theLockExpiresAndFailuresStartCountingFromOneAgain() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure(key);
        }
        assertThat(service.isBlocked(key)).isTrue();

        now.addAndGet(LoginAttemptService.LOCK_MS);
        assertThat(service.isBlocked(key)).isFalse();

        // A single failure after expiry must not immediately re-lock: the window restarted.
        service.recordFailure(key);
        assertThat(service.isBlocked(key)).isFalse();
    }

    @Test
    void failuresOnOnePairDoNotAffectAnother() {
        String other = LoginAttemptService.key("view", "10.0.0.2");
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure(key);
        }
        assertThat(service.isBlocked(key)).isTrue();
        assertThat(service.isBlocked(other)).isFalse();
    }

    @Test
    void keyNormalizesUsernameCaseAndPadding() {
        assertThat(LoginAttemptService.key(" Admin ", "1.2.3.4"))
                .isEqualTo(LoginAttemptService.key("admin", "1.2.3.4"));
    }

    @Test
    void aHugeUsernameIsTruncatedBeforeItBecomesAKey() {
        // The login form caps the field at 64 characters client-side; the server must not
        // retain a multi-megabyte body just because someone posted one.
        String key = LoginAttemptService.key("x".repeat(2_000_000), "1.2.3.4");

        assertThat(key.length())
                .isLessThanOrEqualTo(LoginAttemptService.MAX_USERNAME_LENGTH + 1 + "1.2.3.4".length());
    }

    @Test
    void distinctFailuresNeverGrowTheTrackingMapPastItsCap() {
        // Every unknown username creates an entry. Before the cap, an unauthenticated flood
        // of random usernames retained every one of them for the life of the process.
        int flood = LoginAttemptService.MAX_TRACKED_KEYS + 2_000;
        for (int i = 0; i < flood; i++) {
            service.recordFailure(LoginAttemptService.key("user" + i, "10.0.0.1"));
        }

        assertThat(service.trackedKeys()).isLessThanOrEqualTo(LoginAttemptService.MAX_TRACKED_KEYS);
    }

    @Test
    void pruningKeepsRecentLockoutsAndDropsExpiredOnes() {
        // Fill to the cap inside one window, advance past it, then add one more failure:
        // everything expired should be reclaimed rather than permanently displacing new keys.
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_KEYS; i++) {
            service.recordFailure(LoginAttemptService.key("user" + i, "10.0.0.1"));
        }
        now.addAndGet(LoginAttemptService.LOCK_MS + 1);

        service.recordFailure(key);

        assertThat(service.trackedKeys()).isLessThan(LoginAttemptService.MAX_TRACKED_KEYS);
        assertThat(service.isBlocked(key)).isFalse();
    }
}
