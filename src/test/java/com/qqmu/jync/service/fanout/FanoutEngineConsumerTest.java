package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.cdc.ManagedCdcConsumer;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The wrapper owns the runtime for the JDBC engines: it must be visible to the engine while it
 * runs (that is how the baseline and transactions find the fan-out path) and gone afterwards,
 * and a runtime that cannot even open must not spray workers or leave the registry dirty.
 */
class FanoutEngineConsumerTest {

    private final FanoutRuntimeRegistry registry = new FanoutRuntimeRegistry();
    private final SyncTaskStore taskStore = mock(SyncTaskStore.class);
    private final CdcRuntimeStats stats = new CdcRuntimeStats();

    private static Project project() {
        Project project = new Project();
        project.setId(7L);
        project.setName("fan");
        return project;
    }

    @Test
    void theRuntimeIsRegisteredForTheEngineAndClosedAfterwards() throws Exception {
        FanoutRuntime runtime = mock(FanoutRuntime.class);
        AtomicReference<FanoutRuntime> seenByEngine = new AtomicReference<>();
        ManagedCdcConsumer engine = new ManagedCdcConsumer() {
            @Override
            public void run() {
                seenByEngine.set(registry.get(7L));
            }

            @Override
            public void requestStop() {
            }
        };
        FanoutEngineConsumer consumer = new FanoutEngineConsumer(project(), engine, registry,
                taskStore, stats, () -> runtime);

        consumer.run();

        assertThat(seenByEngine.get()).isSameAs(runtime);
        assertThat(registry.get(7L)).isNull();
        verify(runtime).close();
        verify(taskStore, never()).recordCdcFailure(any(), anyString());
    }

    @Test
    void aTransientRuntimeFailureDoesNotParkTheProject() throws Exception {
        FanoutEngineConsumer consumer = new FanoutEngineConsumer(project(), mock(ManagedCdcConsumer.class),
                registry, taskStore, stats, () -> {
                    throw new SQLTransientConnectionException("target is down");
                });

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
        assertThat(registry.get(7L)).isNull();
    }

    @Test
    void aFatalRuntimeFailureParksTheProject() throws Exception {
        FanoutEngineConsumer consumer = new FanoutEngineConsumer(project(),
                mock(ManagedCdcConsumer.class), registry, taskStore, stats, () -> {
                    // A failure the classifier pins as fatal (auth) — retrying cannot help.
                    throw new IllegalStateException("password authentication failed for user");
                });

        consumer.run();

        verify(taskStore).recordCdcFailure(any(), anyString());
        assertThat(registry.get(7L)).isNull();
    }
}
