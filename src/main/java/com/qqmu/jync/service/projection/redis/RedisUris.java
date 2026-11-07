package com.qqmu.jync.service.projection.redis;

import java.time.Duration;

import com.qqmu.jync.model.RedisTarget;

import io.lettuce.core.RedisURI;

/**
 * The one place a {@link RedisTarget} becomes a {@link RedisURI}.
 *
 * <p>Every client in this package builds its address here so the TLS, timeout and cluster rules
 * cannot drift between the probe and the write path. The password enters the URI in memory only
 * and is never logged by this class.
 */
public final class RedisUris {

    private RedisUris() {
    }

    public static RedisURI from(RedisTarget target, String plainPassword) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(target.getHost())
                .withPort(target.getPort() == null ? 6379 : target.getPort())
                .withTimeout(Duration.ofMillis(
                        target.getConnectTimeoutMs() > 0 ? target.getConnectTimeoutMs() : 5000))
                .withSsl(target.isTlsEnabled());
        if (target.isTlsEnabled()) {
            // verifyTls=false is an explicit, audited operator choice; it turns off both the
            // trust-chain and the hostname check.
            builder.withVerifyPeer(target.isVerifyTls());
        }
        if (!target.isClusterMode() && target.getDatabaseIndex() != null) {
            // A Redis cluster only has database 0; selecting another one is rejected by the
            // driver, so leave the URI at the default and let validation refuse the form.
            builder.withDatabase(target.getDatabaseIndex());
        }
        if (plainPassword != null && !plainPassword.isEmpty()) {
            // Lettuce 7 made RedisURI immutable; credentials are set on the builder.
            if (target.getUsername() != null && !target.getUsername().isBlank()) {
                builder.withAuthentication(target.getUsername(), plainPassword);
            } else {
                builder.withPassword(plainPassword);
            }
        }
        return builder.build();
    }
}
