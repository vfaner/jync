package com.qqmu.jync.service.projection.redis;

/**
 * A failed Redis projection: the transaction did not land, so the CDC position must not advance.
 *
 * <p>Messages name the endpoint (host and port) only. The URI, username and password never reach an
 * exception message or a log line.
 */
public class RedisProjectionException extends RuntimeException {

    public RedisProjectionException(String message) {
        super(message);
    }

    public RedisProjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
