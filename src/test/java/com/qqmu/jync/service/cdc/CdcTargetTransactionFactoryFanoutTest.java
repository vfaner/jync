package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.fanout.ChangeEvent;
import com.qqmu.jync.service.fanout.EventTransport;
import com.qqmu.jync.service.fanout.FanoutRuntime;
import com.qqmu.jync.service.fanout.FanoutRuntimeRegistry;
import com.qqmu.jync.service.fanout.FanoutTargetTransaction;
import com.qqmu.jync.service.fanout.InMemoryEventTransport;

/**
 * A registered fan-out runtime is the only thing that turns the shared transaction seam into
 * a publisher: every JDBC engine keeps calling {@code open(project, ctx)} and gets spool-backed
 * fan-out instead of a direct target write.
 */
class CdcTargetTransactionFactoryFanoutTest {

    private static Project project() {
        Project project = new Project();
        project.setId(11L);
        project.setName("fan");
        return project;
    }

    @Test
    void aRegisteredRuntimeMakesTheSeamPublish() throws Exception {
        FanoutRuntimeRegistry registry = new FanoutRuntimeRegistry();
        FanoutRuntime runtime = mock(FanoutRuntime.class);
        EventTransport transport = new InMemoryEventTransport(4);
        when(runtime.transport()).thenReturn(transport);
        registry.register(11L, runtime);
        EventTransport.Subscription sub = transport.subscribe("t1");

        CdcTargetTransactionFactory factory = new CdcTargetTransactionFactory(
                new CdcRuntimeStats(), 0, null, null, registry);
        CdcTargetTransaction transaction = factory.open(project(), null);

        assertThat(transaction).isInstanceOf(FanoutTargetTransaction.class);
        transaction.notePosition(FanoutRuntime.TOKEN_SCN + "42");
        transaction.commit();

        ChangeEvent marker = sub.poll(Duration.ofSeconds(1));
        assertThat(marker.kind()).isEqualTo(ChangeEvent.EventKind.COMMIT);
        assertThat(marker.position()).isEqualTo("scn:42");
    }

    @Test
    void aRuntimeOfAnotherProjectDoesNotAffectThisOne() {
        FanoutRuntimeRegistry registry = new FanoutRuntimeRegistry();
        registry.register(99L, mock(FanoutRuntime.class));

        assertThat(registry.get(11L)).isNull();
        assertThat(registry.remove(99L)).isNotNull();
        assertThat(registry.get(99L)).isNull();
    }
}
