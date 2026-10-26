package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.Project;

/**
 * A stop request is delivered through the same safe points as a lost lease: the cycle checks
 * it at every committed batch boundary, so stopping a project aborts its in-flight round
 * instead of letting it renew the lease for the rest of a long full load. Without this the
 * user who just pressed "stop sync" is told to "stop the sync first" when saving the config.
 */
class SyncContextStopTest {

    private static SyncContext contextFor(BooleanSupplier stop) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        return SyncContext.builder()
                .project(project)
                .stopRequested(stop)
                .build();
    }

    @Test
    @DisplayName("a stop request aborts the run at the next safe point")
    void aStopRequestAbortsTheRun() {
        AtomicBoolean stop = new AtomicBoolean();
        SyncContext ctx = contextFor(stop::get);

        // Nothing requested yet: the lease check stays a no-op (no renewer on this context).
        assertThatCode(ctx::requireLease).doesNotThrowAnyException();

        stop.set(true);
        assertThatThrownBy(ctx::requireLease)
                .isInstanceOf(StopRequestedException.class)
                .hasMessageContaining("stopped by request");
    }

    @Test
    @DisplayName("contexts nobody can stop keep the plain lease check")
    void aContextWithoutAStopSignalOnlyChecksTheLease() {
        SyncContext ctx = contextFor(null);

        assertThatCode(ctx::requireLease).doesNotThrowAnyException();
    }
}
