package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Audit and change-log rows written outside a login flow need one actor spelling: the
 * signed-in name, or "system" when no authenticated request exists.
 */
class CurrentActorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void backgroundWorkWithNoAuthenticationIsSystem() {
        assertThat(CurrentActor.username()).isEqualTo("system");
    }

    @Test
    void anUnauthenticatedTokenIsStillSystem() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("alice", "pw");
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(CurrentActor.username()).isEqualTo("system");
    }

    @Test
    void theSignedInNameIsUsedWhenAuthenticated() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("alice", "pw");
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThat(CurrentActor.username()).isEqualTo("alice");
    }
}
