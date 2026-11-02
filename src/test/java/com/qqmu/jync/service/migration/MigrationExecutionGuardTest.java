package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MigrationExecutionGuardTest {

    private final MigrationExecutionGuard guard = new MigrationExecutionGuard();

    @Test
    void oneTaskCannotBeAcquiredTwiceUntilItIsReleased() {
        assertThat(guard.tryAcquire(9L)).isTrue();
        assertThat(guard.tryAcquire(9L)).isFalse();
        assertThat(guard.isRunning(9L)).isTrue();

        guard.release(9L);

        assertThat(guard.isRunning(9L)).isFalse();
        assertThat(guard.tryAcquire(9L)).isTrue();
    }

    @Test
    void cancellingOneTaskLeavesOtherRunsAlone() {
        guard.tryAcquire(1L);
        guard.tryAcquire(2L);

        guard.cancel(1L);

        assertThat(guard.cancellation(1L).isCancelled()).isTrue();
        assertThat(guard.cancellation(2L).isCancelled()).isFalse();
    }

    @Test
    void aNewAcquisitionAfterACancelIsNotAlreadyCancelled() {
        guard.tryAcquire(1L);
        guard.cancel(1L);
        guard.release(1L);

        guard.tryAcquire(1L);

        assertThat(guard.cancellation(1L).isCancelled()).isFalse();
    }

    @Test
    void cancellingATaskThatIsNotRunningIsANoOp() {
        guard.cancel(99L);

        assertThat(guard.isRunning(99L)).isFalse();
    }
}
