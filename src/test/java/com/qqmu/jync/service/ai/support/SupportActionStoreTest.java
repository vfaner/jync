package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupportActionStoreTest {

    private final SupportActionStore store = new SupportActionStore();

    @Test
    void aTokenIsSingleUseAndCarriesThePreparedAction() {
        String token = store.put("alice", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.STOP, "订单同步");

        var taken = store.take(token, "alice");

        assertThat(taken).isPresent();
        assertThat(taken.get().kind()).isEqualTo(SupportActionStore.Kind.PROJECT);
        assertThat(taken.get().resourceId()).isEqualTo(7L);
        assertThat(taken.get().action()).isEqualTo(SupportActionStore.Action.STOP);
        assertThat(taken.get().resourceName()).isEqualTo("订单同步");
        assertThat(store.take(token, "alice")).isEmpty();
    }

    /**
     * A confirmation link pasted into another account must change nothing: an operator could
     * otherwise execute an action prepared for someone else. The foreign attempt must not burn
     * the owner's token either.
     */
    @Test
    void anotherAccountCannotTakeTheTokenAndDoesNotBurnIt() {
        String token = store.put("alice", SupportActionStore.Kind.ETL_TASK, 3L,
                SupportActionStore.Action.START, "日汇总");

        assertThat(store.take(token, "bob")).isEmpty();
        assertThat(store.take(token, "alice")).isPresent();
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = store.put("alice", SupportActionStore.Kind.PROJECT, 7L,
                SupportActionStore.Action.START, "订单同步");
        store.expireForTests(token);

        assertThat(store.take(token, "alice")).isEmpty();
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThat(store.take("nope", "alice")).isEmpty();
        assertThat(store.take(null, "alice")).isEmpty();
    }

    @Test
    void theTitleKeyIsDerivedFromKindAndAction() {
        assertThat(SupportActionStore.titleKey(SupportActionStore.Kind.PROJECT,
                SupportActionStore.Action.SYNC_NOW)).isEqualTo("support.action.project.sync_now");
        assertThat(SupportActionStore.titleKey(SupportActionStore.Kind.ETL_TASK,
                SupportActionStore.Action.RUN_NOW)).isEqualTo("support.action.etl_task.run_now");
    }
}
