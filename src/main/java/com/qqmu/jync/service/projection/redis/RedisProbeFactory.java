package com.qqmu.jync.service.projection.redis;

import com.qqmu.jync.model.RedisTarget;

/**
 * Opens a short-lived, read-only connection to a Redis target.
 *
 * <p>A seam: the precheck's rules are tested without a server, and the real factory is the only
 * place that decrypts the target password and builds the Lettuce client.
 */
public interface RedisProbeFactory {

    RedisProbe open(RedisTarget target);
}
