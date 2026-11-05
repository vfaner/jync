package com.qqmu.jync.service.projection.redis;

import java.util.List;

import org.springframework.stereotype.Component;

import com.qqmu.jync.model.RedisTarget;

/**
 * Executes the mutations of one CDC transaction as a single pipeline.
 *
 * <p>Not a Spring transaction manager: {@code MULTI/EXEC} is deliberately unused (a cluster would
 * reject a multi-key transaction across slots, and a cache does not need cross-key atomicity). The
 * contract is "send the whole transaction, then check every reply" — a failure throws, the consumer
 * keeps its position, and replay converges because every command is idempotent.
 */
@Component
public class RedisProjectionWriter {

    private final RedisClients clients;

    public RedisProjectionWriter(RedisClients clients) {
        this.clients = clients;
    }

    public void apply(RedisTarget target, List<RedisMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return;
        }
        RedisCommandSink sink;
        try {
            sink = clients.sink(target);
        } catch (RuntimeException e) {
            throw failed(target, e);
        }
        try {
            for (RedisMutation mutation : mutations) {
                applyOne(sink, mutation);
            }
            sink.flush();
        } catch (RedisProjectionException e) {
            throw e;
        } catch (Exception e) {
            throw failed(target, e);
        } finally {
            try {
                sink.close();
            } catch (RuntimeException ignored) {
                // The transaction already failed or committed; a broken close must not mask it.
            }
        }
    }

    private static void applyOne(RedisCommandSink sink, RedisMutation mutation) {
        switch (mutation.kind()) {
            case SET -> sink.set(mutation.key(), mutation.jsonValue());
            case HSET -> {
                if (!mutation.fields().isEmpty()) {
                    sink.hset(mutation.key(), mutation.fields());
                }
                if (!mutation.deleteFields().isEmpty()) {
                    sink.hdel(mutation.key(), mutation.deleteFields());
                }
            }
            case DEL -> sink.del(mutation.key());
        }
        if (mutation.kind() != RedisMutation.Kind.DEL && mutation.hasTtl()) {
            sink.expire(mutation.key(), mutation.ttlSeconds());
        }
    }

    private static RedisProjectionException failed(RedisTarget target, Exception cause) {
        String endpoint = target == null ? "Redis target"
                : target.getHost() + ":" + target.getPort();
        return new RedisProjectionException("Redis projection to " + endpoint + " failed", cause);
    }
}
