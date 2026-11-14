package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * The metadata-store lease behind {@link EtlExecutionGuard}, against a real H2 schema.
 *
 * <p>An in-process guard cannot see a second instance: with two nodes on one schedule, both
 * would delete the same range or insert the same rows. The lease is a row per task/batch key
 * with an owner and an expiry; the conditional UPDATE is the guard, and the expiry is what lets
 * a crashed node's claim be reclaimed instead of blocking its task forever.
 */
@DataJpaTest
@Import(JpaEtlLeaseStore.class)
class JpaEtlLeaseStoreTest {

    private static final long MINUTE = 60_000L;
    private static final String NODE_A = "node-a";
    private static final String NODE_B = "node-b";

    @Autowired
    private EtlLeaseStore store;

    @Test
    void anUnclaimedKeyIsNeitherHeldNorClaimable() {
        assertThat(store.exists("task:absent")).isFalse();
        // No row yet: the guard creates it with an insert after this refusal.
        assertThat(store.claim("task:absent", NODE_A, MINUTE)).isFalse();
    }

    @Test
    void exactlyOneOwnerHoldsAFreshLease() {
        store.insert("task:1", NODE_A, MINUTE);

        assertThat(store.claim("task:1", NODE_B, MINUTE)).isFalse();
        // The holder re-entering is not a conflict: a retry of the same run must not be
        // permanently refused after a transient failure.
        assertThat(store.claim("task:1", NODE_A, MINUTE)).isTrue();
    }

    @Test
    void anExpiredLeaseIsTakenOver() {
        // A crashed owner never releases; the lease has to expire by itself.
        store.insert("task:2", NODE_A, -MINUTE);

        assertThat(store.claim("task:2", NODE_B, MINUTE)).isTrue();
        assertThat(store.claim("task:2", NODE_A, MINUTE)).isFalse();
    }

    @Test
    void onlyTheOwnerCanRenew() {
        store.insert("task:3", NODE_A, MINUTE);

        assertThat(store.renew("task:3", NODE_B, MINUTE)).isFalse();
        assertThat(store.renew("task:3", NODE_A, MINUTE)).isTrue();
    }

    @Test
    void releasingDeletesOnlyTheOwnersRow() {
        store.insert("task:4", NODE_A, MINUTE);

        // A non-owner's release must not drop someone else's claim.
        store.release("task:4", NODE_B);
        assertThat(store.exists("task:4")).isTrue();
        assertThat(store.claim("task:4", NODE_B, MINUTE)).isFalse();

        // The owner's release removes the row, so the next runner starts from insert(), not
        // claim() — exactly what the guard does when it sees no row.
        store.release("task:4", NODE_A);
        assertThat(store.exists("task:4")).isFalse();
        store.insert("task:4", NODE_B, MINUTE);
        assertThat(store.claim("task:4", NODE_A, MINUTE)).isFalse();
    }

    /**
     * A node killed mid-run leaves its row behind with an expiry in the past. Destructive
     * operations consult {@code isLeased}, so counting that row as a live run would leave the
     * task impossible to delete (or to change its extraction SQL) forever — the row is never
     * cleaned up by anything else.
     */
    @Test
    void anExpiredRowDoesNotLookLikeALiveRun() {
        store.insert("task:5", NODE_A, -MINUTE);
        EtlExecutionGuard guard = new EtlExecutionGuard(store, NODE_B);

        assertThat(guard.isLeased("task:5")).isFalse();
    }

    @Test
    void discardingRemovesTheRowSoADeletedTaskLeavesNothingBehind() {
        store.insert("batch:9", NODE_A, MINUTE);

        store.discard("batch:9");

        assertThat(store.exists("batch:9")).isFalse();
        store.insert("batch:9", NODE_B, MINUTE);
        assertThat(store.claim("batch:9", NODE_A, MINUTE)).isFalse();
    }
}
