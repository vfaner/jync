package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.util.CryptoUtil;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.cluster.RedisClusterClient;

/**
 * A Lettuce client owns the Netty event loop. When {@code connect()} fails, nothing is cached
 * for that target, so the next CDC transaction builds another client: without a shutdown each
 * failed attempt leaks one event loop for the life of the process.
 */
class RedisClientsShutdownTest {

    private final RedisClients clients = new RedisClients(mock(CryptoUtil.class));

    @Test
    void aStandaloneClientWhoseConnectFailsIsShutDown() {
        RedisClient client = mock(RedisClient.class);
        when(client.connect()).thenThrow(new RedisConnectionException("Connection refused"));

        assertThatThrownBy(() -> clients.open(client, Duration.ofSeconds(1), 1L))
                .isInstanceOf(RedisConnectionException.class);

        verify(client).shutdown();
    }

    @Test
    void aClusterClientWhoseConnectFailsIsShutDown() {
        RedisClusterClient client = mock(RedisClusterClient.class);
        when(client.connect()).thenThrow(new RedisConnectionException("Connection refused"));

        assertThatThrownBy(() -> clients.openCluster(client, Duration.ofSeconds(1), 1L))
                .isInstanceOf(RedisConnectionException.class);

        verify(client).shutdown();
    }
}
