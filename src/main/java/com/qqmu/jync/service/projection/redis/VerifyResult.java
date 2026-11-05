package com.qqmu.jync.service.projection.redis;

/**
 * The result of a Redis connection probe.
 *
 * <p>A failure is data, not an exception: the connection form shows it inline, exactly like the
 * JDBC connection test does. {@code detail} is for the operator only and never carries the
 * password.
 */
public record VerifyResult(boolean ok, String messageKey, String detail) {
}
