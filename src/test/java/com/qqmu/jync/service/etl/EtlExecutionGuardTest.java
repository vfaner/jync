package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A task and a batch are separate execution slots even when their database ids coincide. The
 * string overloads are what lets a batch hold its own key; the Long overloads must keep meaning
 * "task", exactly as before.
 *
 * <p>The second half covers the shared lease: the in-memory set only sees this process, so two
 * instances scheduling the same task would both delete the same range or insert the same rows.
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

    // --- mutation leases: hold the key across a definition change, not at a point in time ---

    @Test
    void aMutationLeaseIsRefusedWhenTheKeyIsAlreadyRunning() {
        guard.tryAcquire(5L);

        EtlExecutionGuard.MutationLease lease = guard.lockForMutation(5L);

        assertThat(lease.acquired()).isFalse();
        guard.release(5L);
        assertThat(guard.isLeased(5L)).isFalse();
    }

    @Test
    void aMutationLeaseOutsideATransactionReleasesOnClose() {
        try (EtlExecutionGuard.MutationLease lease = guard.lockForMutation(5L)) {
            assertThat(lease.acquired()).isTrue();
            assertThat(guard.isLeased(5L)).isTrue();
        }

        assertThat(guard.isLeased(5L)).isFalse();
    }

    @Test
    void aMutationLeaseInsideATransactionOutlivesCloseUntilTheTransactionCompletes() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (EtlExecutionGuard.MutationLease lease = guard.lockForMutation(5L)) {
                assertThat(lease.acquired()).isTrue();
            }
            // The mutation's own commit is still ahead: releasing here would reopen the
            // window this lease exists to close.
            assertThat(guard.isLeased(5L)).isTrue();

            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(guard.isLeased(5L)).isFalse();
    }

    // --- shared lease across instances -----------------------------------------------------

    private final FakeLeaseStore store = new FakeLeaseStore();
    private final EtlExecutionGuard nodeA = new EtlExecutionGuard(store, "node-a");
    private final EtlExecutionGuard nodeB = new EtlExecutionGuard(store, "node-b");

    @Test
    void aSecondNodeCannotRunTheSameTask() {
        assertThat(nodeA.tryAcquire(1L)).isTrue();

        assertThat(nodeB.tryAcquire(1L)).isFalse();
        // The refusal must not leave the key held in this node's own set, or a later run
        // could never start here even after the other node finished.
        assertThat(nodeB.isRunning(1L)).isFalse();
    }

    @Test
    void theNodeThatRanTheTaskCanRunItAgain() {
        assertThat(nodeA.tryAcquire(1L)).isTrue();
        nodeA.release(1L);

        assertThat(nodeA.tryAcquire(1L)).isTrue();
        assertThat(nodeB.tryAcquire(1L)).isFalse();
    }

    @Test
    void releasingOnOneNodeFreesTheTaskForTheOther() {
        nodeA.tryAcquire(1L);

        nodeA.release(1L);

        assertThat(nodeB.tryAcquire(1L)).isTrue();
    }

    @Test
    void aSecondNodeSeesATaskRunningElsewhere() {
        nodeA.tryAcquire(1L);

        // isRunning is the cheap local view the list pages render; isLeased is what the
        // destructive operations consult, and it sees the other node's live lease.
        assertThat(nodeB.isRunning(1L)).isFalse();
        assertThat(nodeB.isLeased(1L)).isTrue();
        assertThat(store.owns("task:1", "node-a")).isTrue();
    }

    @Test
    void aFailedLeaseStoreRefusesTheRunWithoutLeakingTheLocalSlot() {
        store.failEverything = true;

        assertThat(nodeA.tryAcquire(1L)).isFalse();
        assertThat(nodeA.isRunning(1L)).isFalse();

        store.failEverything = false;
        assertThat(nodeA.tryAcquire(1L)).isTrue();
    }

    @Test
    void losingTheLeaseToAnotherNodeIsReportedOnceAndStopsRenewal() {
        nodeA.tryAcquire(1L);
        // What another node's expiry takeover looks like to this one: the row is no longer ours.
        store.forceOwner("task:1", "node-b");

        nodeA.renewLeases();
        store.renewCalls = 0;
        nodeA.renewLeases();

        assertThat(store.renewCalls).isZero();
        // The local run keeps its slot until it finishes: freeing it now would let a third
        // run start on top of a copy that is still writing.
        assertThat(nodeA.isRunning(1L)).isTrue();
    }

    @Test
    void anExpiredLeaseElsewhereDoesNotBlockDestructiveOperations() {
        nodeA.tryAcquire(1L);
        // What a killed node leaves behind: the row is still there, its expiry is not.
        store.expire("task:1");

        assertThat(store.isLive("task:1")).isFalse();
        assertThat(nodeB.isLeased(1L)).isFalse();
    }

    @Test
    void aDeletedTaskLeavesNoLeaseBehind() {
        nodeA.tryAcquire(1L);

        nodeA.discard(1L);

        assertThat(store.exists("task:1")).isFalse();
        assertThat(nodeA.isRunning(1L)).isFalse();
    }

    /** In-memory stand-in with the same ownership rules as the JDBC store. */
    private static final class FakeLeaseStore implements EtlLeaseStore {

        private final Map<String, String> owners = new HashMap<>();
        private final java.util.Set<String> expired = new java.util.HashSet<>();
        boolean failEverything;
        int renewCalls;

        @Override
        public boolean exists(String key) {
            failIfAsked();
            return owners.containsKey(key);
        }

        @Override
        public boolean isLive(String key) {
            failIfAsked();
            return owners.containsKey(key) && !expired.contains(key);
        }

        void expire(String key) {
            expired.add(key);
        }

        @Override
        public void insert(String key, String owner, long ttlMs) {
            failIfAsked();
            owners.putIfAbsent(key, owner);
        }

        @Override
        public boolean claim(String key, String owner, long ttlMs) {
            failIfAsked();
            String current = owners.get(key);
            if (current == null) {
                return false;
            }
            if (!current.equals(owner)) {
                return false;
            }
            owners.put(key, owner);
            return true;
        }

        @Override
        public boolean renew(String key, String owner, long ttlMs) {
            failIfAsked();
            renewCalls++;
            return owner.equals(owners.get(key));
        }

        @Override
        public void release(String key, String owner) {
            failIfAsked();
            owners.remove(key, owner);
        }

        @Override
        public void discard(String key) {
            failIfAsked();
            owners.remove(key);
        }

        boolean owns(String key, String owner) {
            return owner.equals(owners.get(key));
        }

        void forceOwner(String key, String owner) {
            owners.put(key, owner);
        }

        private void failIfAsked() {
            if (failEverything) {
                throw new IllegalStateException("metadata store unreachable");
            }
        }
    }
}
