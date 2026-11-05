package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.RedisProjectionConfig;

/**
 * The contract recorded with a finished baseline: what a later start or rebuild must compare
 * against to detect a key type or namespace change.
 */
class RedisProjectionSignatureTest {

    @Test
    void encodesFormatAndPrefix() {
        RedisProjectionConfig projection = RedisProjectionConfig.defaultFor(7L);

        assertThat(RedisProjectionSignature.of(projection)).isEqualTo("JSON|jync:7:");
    }

    @Test
    void formatIsPartOfTheContract() {
        RedisProjectionConfig projection = RedisProjectionConfig.defaultFor(7L);
        projection.setValueFormat(RedisValueFormat.HASH);

        assertThat(RedisProjectionSignature.of(projection)).isEqualTo("HASH|jync:7:");
    }

    @Test
    void aPrefixMayContainTheSeparator() {
        RedisProjectionConfig projection = RedisProjectionConfig.defaultFor(7L);
        projection.setKeyPrefix("team|a:");

        String signature = RedisProjectionSignature.of(projection);

        assertThat(signature).isEqualTo("JSON|team|a:");
        assertThat(RedisProjectionSignature.prefixOf(signature)).isEqualTo("team|a:");
    }

    @Test
    void aMissingContractHasNoSignature() {
        assertThat(RedisProjectionSignature.of(null)).isNull();
        assertThat(RedisProjectionSignature.of(new RedisProjectionConfig())).isNull();
    }

    @Test
    void anUnreadableSignatureHasNoPrefix() {
        assertThat(RedisProjectionSignature.prefixOf(null)).isNull();
        assertThat(RedisProjectionSignature.prefixOf("")).isNull();
        assertThat(RedisProjectionSignature.prefixOf("garbage")).isNull();
        assertThat(RedisProjectionSignature.prefixOf("JSON|")).isNull();
    }
}
