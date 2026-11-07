package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.RedisProjectionConfig;

/**
 * The default key prefix is written in three places with different reasons: the fresh-project
 * contract, the blank-prefix normalisation at save/start, and the re-key step of a clone. They
 * must produce the same string — a clone keyed {@code jync:<newId>:} while the policy derived
 * something else would collide with the source's namespace.
 */
class RedisProjectionDefaultPrefixTest {

    @Test
    void blankPrefixNormalizesToTheSameDefaultAProjectIsCreatedWith() {
        assertThat(RedisProjectionPolicy.normalizePrefix(null, 42L))
                .isEqualTo(RedisProjectionConfig.defaultFor(42L).getKeyPrefix())
                .isEqualTo("jync:42:");
    }

    @Test
    void aPrefixWithoutTrailingColonGetsOne() {
        assertThat(RedisProjectionPolicy.normalizePrefix(" app ", 1L)).isEqualTo("app:");
    }
}
