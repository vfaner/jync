package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A task and a batch are separate execution slots even when their database ids coincide. The
 * string overloads are what lets a batch hold its own key; the Long overloads must keep meaning
 * "task", exactly as before.
 */
class EtlExecutionGuardTest {

    private final EtlExecutionGuard guard = new EtlExecutionGuard();

    @Test
    void taskAndBatchKeysDoNotCollide() {
        assertThat(guard.tryAcquire(1L)).isTrue();

        assertThat(guard.tryAcquire("batch:1")).isTrue();
        assertThat(guard.isRunning(1L)).isTrue();
        assertThat(guard.isRunning("batch:1")).isTrue();
    }

    @Test
    void releasingOneKeyLeavesTheOtherHeld() {
        guard.tryAcquire(1L);
        guard.tryAcquire("batch:1");

        guard.release(1L);

        assertThat(guard.isRunning(1L)).isFalse();
        assertThat(guard.isRunning("batch:1")).isTrue();
    }

    @Test
    void longOverloadsUseTheTaskNamespace() {
        guard.tryAcquire(7L);

        assertThat(guard.isRunning("task:7")).isTrue();

        guard.release("task:7");

        assertThat(guard.isRunning(7L)).isFalse();
    }
}
