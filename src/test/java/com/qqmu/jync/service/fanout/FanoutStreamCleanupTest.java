package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

/**
 * Deleting a project must not leave its Redis Stream behind, and must never fail the delete
 * because Redis is unreachable — the stream is operational state, the project row is not.
 */
class FanoutStreamCleanupTest {

    @Test
    void theStreamKeyIsDerivedFromTheProjectId() {
        assertThat(FanoutStreamCleanup.streamKey(7L)).isEqualTo("jync:fanout:7:events");
    }

    @Test
    void theSpoolTransportHasNothingToDelete() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.SPOOL);
        props.getFanout().setRedisUri("redis://127.0.0.1:1/0");

        // Would hang/fail if the URI were dialed; SPOOL must return before any connection.
        assertThatCode(() -> new FanoutStreamCleanup(props).delete(7L))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreachableRedisDoesNotFailTheDelete() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.REDIS);
        props.getFanout().setRedisUri("redis://127.0.0.1:1/0");

        assertThatCode(() -> new FanoutStreamCleanup(props).delete(7L))
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingProjectIdIsANoOp() {
        SyncProperties props = new SyncProperties();
        props.getFanout().setTransport(SyncProperties.Fanout.Transport.REDIS);

        assertThatCode(() -> new FanoutStreamCleanup(props).delete(null))
                .doesNotThrowAnyException();
    }
}
