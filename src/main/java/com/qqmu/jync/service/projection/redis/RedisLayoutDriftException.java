package com.qqmu.jync.service.projection.redis;

/**
 * A HASH projection saw a table change its column layout while the stream was running.
 *
 * <p>The message carries {@code CdcSchemaDrift.MARKER} on purpose: the consumer's failure
 * classifier turns that marker into a schema-drift park (ERROR hat plus a rebuild prompt), which is
 * the only correct response. A HASH cannot rewrite a row wholesale, so a dropped column's field
 * would stay in Redis forever; re-baselining is what clears it.
 */
public class RedisLayoutDriftException extends RuntimeException {

    public RedisLayoutDriftException(String message) {
        super(message);
    }
}
