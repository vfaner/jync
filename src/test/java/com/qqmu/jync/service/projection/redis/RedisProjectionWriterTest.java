package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.model.RedisTarget;

import io.lettuce.core.RedisCommandExecutionException;

class RedisProjectionWriterTest {

    private final RedisClients clients = mock(RedisClients.class);
    private final RedisCommandSink sink = mock(RedisCommandSink.class);
    private final RedisTarget target = new RedisTarget();
    private final RedisProjectionWriter writer = new RedisProjectionWriter(clients);

    @Test
    @DisplayName("commands stay buffered in the sink and are flushed exactly once")
    void commitOnlySendsBufferedCommands() throws Exception {
        when(clients.sink(target)).thenReturn(sink);

        writer.apply(target, List.of(
                new RedisMutation(RedisMutation.Kind.SET, "k1", Map.of(), "{}", List.of(), 0),
                new RedisMutation(RedisMutation.Kind.DEL, "k2", Map.of(), null, List.of(), 0)));

        InOrder order = inOrder(sink);
        order.verify(sink).set("k1", "{}");
        order.verify(sink).del("k2");
        order.verify(sink).flush();
        order.verify(sink).close();
    }

    @Test
    @DisplayName("a HASH upsert writes the fields and deletes the vanished ones")
    void hashMutationWritesAndDeletesFields() throws Exception {
        when(clients.sink(target)).thenReturn(sink);

        writer.apply(target, List.of(new RedisMutation(RedisMutation.Kind.HSET, "k1",
                Map.of("id", "1"), null, List.of("memo"), 0)));

        verify(sink).hset("k1", Map.of("id", "1"));
        verify(sink).hdel("k1", List.of("memo"));
    }

    @Test
    @DisplayName("a positive TTL emits EXPIRE and a zero TTL does not")
    void ttlAddsExpire() throws Exception {
        when(clients.sink(target)).thenReturn(sink);

        writer.apply(target, List.of(
                new RedisMutation(RedisMutation.Kind.SET, "expiring", Map.of(), "{}", List.of(), 30),
                new RedisMutation(RedisMutation.Kind.SET, "lasting", Map.of(), "{}", List.of(), 0)));

        verify(sink).expire("expiring", 30);
        verify(sink, times(1)).expire(anyString(), anyLong());
    }

    @Test
    @DisplayName("an empty transaction opens no connection")
    void emptyListDoesNotOpenASink() throws Exception {
        writer.apply(target, List.of());

        verifyNoInteractions(clients);
    }

    @Test
    @DisplayName("a failed reply surfaces as a projection failure, never silently")
    void everyReplyIsCheckedAndAnErrorIsThrown() throws Exception {
        when(clients.sink(target)).thenReturn(sink);
        doThrow(new RedisCommandExecutionException("WRONGTYPE"))
                .when(sink).flush();

        assertThatThrownBy(() -> writer.apply(target, List.of(
                new RedisMutation(RedisMutation.Kind.SET, "k", Map.of(), "{}", List.of(), 0))))
                .isInstanceOf(RedisProjectionException.class);
    }

    @Test
    @DisplayName("the borrowed connection is released even when the flush fails")
    void sinkIsClosedOnFailure() throws Exception {
        when(clients.sink(target)).thenReturn(sink);
        doThrow(new RedisCommandExecutionException("OOM")).when(sink).flush();

        assertThatThrownBy(() -> writer.apply(target, List.of(
                new RedisMutation(RedisMutation.Kind.DEL, "k", Map.of(), null, List.of(), 0))))
                .isInstanceOf(RedisProjectionException.class);

        verify(sink).close();
    }

    @Test
    @DisplayName("a connection failure is wrapped without leaking credentials")
    void connectionFailureIsWrapped() {
        when(clients.sink(target)).thenThrow(new IllegalStateException("connect failed"));

        assertThatThrownBy(() -> writer.apply(target, List.of(
                new RedisMutation(RedisMutation.Kind.DEL, "k", Map.of(), null, List.of(), 0))))
                .isInstanceOf(RedisProjectionException.class);
    }
}
