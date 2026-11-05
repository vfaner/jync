package com.qqmu.jync.service.projection.redis;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;

/**
 * Opens the real connection behind {@link RedisProbe}.
 *
 * <p>Short-lived on purpose: a precheck is a one-off, and caching a probe connection would add
 * a lifetime to manage for no benefit. The address comes from {@link RedisUris}, the same place
 * the write path builds it, so TLS and database rules cannot drift between them.
 *
 * <p>The password is decrypted here and nowhere else; a driver message is scrubbed of it
 * before it leaves this class, because the precheck copies failures into a report the browser
 * renders.
 */
@Component
public class LettuceRedisProbeFactory implements RedisProbeFactory {

    private final CryptoUtil cryptoUtil;

    public LettuceRedisProbeFactory(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Override
    public RedisProbe open(RedisTarget target) {
        String password = target.getPasswordEnc() == null || target.getPasswordEnc().isEmpty()
                ? null
                : cryptoUtil.decrypt(target.getPasswordEnc());
        RedisURI uri = RedisUris.from(target, password);
        Duration timeout = Duration.ofMillis(
                target.getCommandTimeoutMs() > 0 ? target.getCommandTimeoutMs() : 10_000);

        if (target.isClusterMode()) {
            RedisClusterClient client = RedisClusterClient.create(uri);
            try {
                client.setDefaultTimeout(timeout);
                StatefulRedisClusterConnection<String, String> connection = client.connect();
                return new LettuceRedisProbe(connection.sync(), () -> {
                    connection.close();
                    client.shutdown();
                });
            } catch (RuntimeException e) {
                client.shutdown();
                throw sanitized(e, password);
            }
        }

        RedisClient client = RedisClient.create(uri);
        try {
            client.setDefaultTimeout(timeout);
            StatefulRedisConnection<String, String> connection = client.connect();
            return new LettuceRedisProbe(connection.sync(), () -> {
                connection.close();
                client.shutdown();
            });
        } catch (RuntimeException e) {
            client.shutdown();
            throw sanitized(e, password);
        }
    }

    /** A driver message with the credential removed and its length bounded for a page. */
    private static RuntimeException sanitized(RuntimeException e, String password) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (password != null && !password.isEmpty()) {
            message = message.replace(password, "***");
        }
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        return new RuntimeException(message, e);
    }
}
