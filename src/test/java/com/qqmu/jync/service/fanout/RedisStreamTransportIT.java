package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Real Redis 7: the two consumer-group start modes and the foreign-entry skip.
 *
 * <p>A group created for a baseline run must start at the stream tail, because the baseline
 * republishes everything the target needs. A group created during a normal resume must start
 * at the head and replay the backlog. Getting this backwards either replays weeks of history
 * or silently skips events.
 *
 * <pre>
 * JYNC_REDIS_IT_URI=redis://127.0.0.1:6390 mvn -q test -Dtest=RedisStreamTransportIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "JYNC_REDIS_IT_URI", matches = ".+")
class RedisStreamTransportIT {

    private static RedisURI uri() {
        return RedisURI.create(System.getenv("JYNC_REDIS_IT_URI"));
    }

    private static String key() {
        return "jync:fanout:it:" + UUID.randomUUID();
    }

    @Test
    void aNewBaselineGroupStartsAtTheTailAndOnlySeesNewEvents() throws Exception {
        String key = key();
        RedisStreamEventTransport transport = new RedisStreamEventTransport(uri(), key, 0L, true);
        try {
            transport.publish(row("0/1", "old-before-baseline"));
            EventTransport.Subscription sub = transport.subscribe("tail-" + key);
            transport.publish(row("0/2", "after-baseline"));

            ChangeEvent event = sub.poll(Duration.ofSeconds(3));
            assertThat(event).isNotNull();
            assertThat(event.transactionId()).isEqualTo("after-baseline");
        } finally {
            transport.close();
            delete(key);
        }
    }

    @Test
    void aNormalResumeGroupReplaysTheBacklog() throws Exception {
        String key = key();
        RedisStreamEventTransport transport = new RedisStreamEventTransport(uri(), key, 0L, false);
        try {
            transport.publish(row("0/1", "backlog"));
            EventTransport.Subscription sub = transport.subscribe("head-" + key);

            ChangeEvent event = sub.poll(Duration.ofSeconds(3));
            assertThat(event).isNotNull();
            assertThat(event.transactionId()).isEqualTo("backlog");
        } finally {
            transport.close();
            delete(key);
        }
    }

    @Test
    void aForeignEntryIsSkippedWithoutBlockingTheNextJyncEvent() throws Exception {
        String key = key();
        RedisStreamEventTransport transport = new RedisStreamEventTransport(uri(), key, 0L, false);
        RedisClient raw = RedisClient.create(uri());
        try {
            transport.publish(row("0/1", "first"));
            EventTransport.Subscription sub = transport.subscribe("foreign-" + key);
            RedisCommands<String, String> commands = raw.connect().sync();
            commands.xadd(key, Map.of("not-jync", "value"));
            transport.publish(row("0/2", "second"));

            ChangeEvent first = sub.poll(Duration.ofSeconds(3));
            assertThat(first).isNotNull();
            assertThat(first.transactionId()).isEqualTo("first");

            // The foreign entry yields null once (acknowledged and skipped); the real event
            // after it must still arrive. Poll a few times because a null can also mean the
            // reader momentarily caught up.
            ChangeEvent next = null;
            for (int i = 0; i < 5 && next == null; i++) {
                next = sub.poll(Duration.ofSeconds(1));
            }
            assertThat(next).isNotNull();
            assertThat(next.transactionId()).isEqualTo("second");
        } finally {
            transport.close();
            raw.close();
            delete(key);
        }
    }

    private static ChangeEvent row(String position, String tx) {
        return ChangeEvent.row(position, tx, "app", "orders",
                ChangeEvent.ChangeOperation.UPSERT, Map.of("id", 1L), null, 1L);
    }

    private static void delete(String key) {
        RedisClient client = RedisClient.create(uri());
        try {
            client.connect().sync().del(key);
        } finally {
            client.close();
        }
    }
}
