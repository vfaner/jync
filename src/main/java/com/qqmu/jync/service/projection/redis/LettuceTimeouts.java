package com.qqmu.jync.service.projection.redis;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;

/**
 * Lettuce 7 removed {@code setDefaultTimeout}; a command timeout is now part of the client
 * options. Kept in one place so the probe, the sweeper and the CDC writer cannot drift.
 */
final class LettuceTimeouts {

    private LettuceTimeouts() {
    }

    static ClientOptions client(Duration timeout) {
        return ClientOptions.builder().timeoutOptions(options(timeout)).build();
    }

    static ClusterClientOptions cluster(Duration timeout) {
        return ClusterClientOptions.builder().timeoutOptions(options(timeout)).build();
    }

    private static TimeoutOptions options(Duration timeout) {
        return TimeoutOptions.builder().timeoutCommands().fixedTimeout(timeout).build();
    }
}
