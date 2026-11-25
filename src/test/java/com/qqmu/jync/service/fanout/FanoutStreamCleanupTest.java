package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.projection.redis.RedisTargetService;
import com.qqmu.jync.util.CryptoUtil;

/**
 * Deleting a project must not leave its Redis Stream behind, and must never fail the delete
 * because Redis is unreachable — the stream is operational state, the project row is not.
 */
class FanoutStreamCleanupTest {

    @Test
    void theStreamKeyIsDerivedFromTheProjectId() {
        assertThat(FanoutStreamCleanup.streamKey(7L)).isEqualTo("jync:fanout:7:events");
    }

    private static Project project() {
        Project project = new Project();
        project.setId(7L);
        project.setName("fan-out");
        return project;
    }

    private static SyncConfig config(String transport) {
        SyncConfig config = new SyncConfig();
        config.setFanoutTransport(transport);
        return config;
    }

    @Test
    void theSpoolTransportHasNothingToDelete() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.SPOOL);
        props.getFanout().setRedisUri("redis://127.0.0.1:1/0");

        // Would hang/fail if the URI were dialed; SPOOL must return before any connection.
        assertThatCode(() -> new FanoutStreamCleanup(props, mock(RedisTargetService.class),
                mock(CryptoUtil.class)).delete(project(), config("SPOOL")))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreachableRedisDoesNotFailTheDelete() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.SPOOL);
        props.getFanout().setRedisUri("redis://127.0.0.1:1/0");
        RedisTargetService targets = mock(RedisTargetService.class);
        when(targets.find(3L)).thenReturn(Optional.empty());

        assertThatCode(() -> new FanoutStreamCleanup(props, targets,
                mock(CryptoUtil.class)).delete(project(), config("REDIS")))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingProjectIdIsANoOp() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.REDIS);

        assertThatCode(() -> new FanoutStreamCleanup(props, mock(RedisTargetService.class),
                mock(CryptoUtil.class)).delete(null, config("REDIS")))
                .doesNotThrowAnyException();
    }
}
