package com.qqmu.jync.service.fanout;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.cdc.CdcFailureClassifier;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.cdc.ManagedCdcConsumer;
import com.qqmu.jync.service.task.SyncTaskStore;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs one of the existing JDBC capture engines as the capture side of a fan-out project.
 *
 * <p>The engine is untouched: it still decodes one source stream and calls its usual
 * transaction seam. This wrapper only owns the runtime — it opens it (target contexts,
 * spools and one worker per target) before the engine starts and closes it when the engine
 * stops. Registering it in {@link FanoutRuntimeRegistry} is what redirects the engine's
 * baseline and target transactions to the fan-out path.
 */
@Slf4j
public final class FanoutEngineConsumer implements ManagedCdcConsumer {

    /** Builds and starts the runtime; checked because target connections are opened here. */
    @FunctionalInterface
    public interface RuntimeOpener {
        FanoutRuntime open() throws Exception;
    }

    private final Project project;
    private final ManagedCdcConsumer engine;
    private final FanoutRuntimeRegistry registry;
    private final SyncTaskStore taskStore;
    private final CdcRuntimeStats stats;
    private final RuntimeOpener opener;

    public FanoutEngineConsumer(Project project, ManagedCdcConsumer engine,
                                FanoutRuntimeRegistry registry, SyncTaskStore taskStore,
                                CdcRuntimeStats stats, RuntimeOpener opener) {
        this.project = project;
        this.engine = engine;
        this.registry = registry;
        this.taskStore = taskStore;
        this.stats = stats;
        this.opener = opener;
    }

    @Override
    public void run() {
        Long projectId = project.getId();
        FanoutRuntime runtime = null;
        try {
            runtime = opener.open();
            registry.register(projectId, runtime);
            engine.run();
        } catch (Exception e) {
            String detail = CdcFailureClassifier.describe(e);
            stats.setLastError(detail);
            if (CdcFailureClassifier.isTransient(e)) {
                log.warn("Fan-out runtime for project {} could not start (transient, the next"
                        + " supervise tick retries): {}", projectId, detail);
                // Stack at DEBUG: the retry loop runs every supervise tick, and a stack per
                // tick buries the one-line cause the operator needs.
                log.debug("Fan-out runtime start failure for project {}", projectId, e);
            } else {
                log.error("Fan-out runtime for project {} failed: {}", projectId, detail, e);
                taskStore.recordCdcFailure(projectId, detail);
            }
        } finally {
            registry.remove(projectId, runtime);
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    @Override
    public void requestStop() {
        engine.requestStop();
    }
}
