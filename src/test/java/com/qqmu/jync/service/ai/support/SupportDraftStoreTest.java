package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.Project;

class SupportDraftStoreTest {

    private final Project project = new Project();

    @Test
    void aTokenIsUsableOnce() {
        SupportDraftStore store = new SupportDraftStore();
        String token = store.put("alice", project, "suggest");

        assertThat(store.take(token, "alice")).isPresent();
        assertThat(store.take(token, "alice")).isEmpty();
    }

    @Test
    void aTokenBelongsToItsUser() {
        SupportDraftStore store = new SupportDraftStore();
        String token = store.put("alice", project, null);
        assertThat(store.take(token, "bob")).isEmpty();
    }

    @Test
    void anExpiredTokenIsRefused() {
        SupportDraftStore store = new SupportDraftStore();
        String token = store.put("alice", project, null);
        store.expireForTests(token);
        assertThat(store.take(token, "alice")).isEmpty();
    }

    @Test
    void tokensAreLongHexAndDistinct() {
        SupportDraftStore store = new SupportDraftStore();
        String a = store.put("alice", project, null);
        String b = store.put("alice", project, null);
        assertThat(a).hasSize(32).matches("[0-9a-f]+").isNotEqualTo(b);
    }

    @Test
    void expiredTokensDoNotAccumulate() {
        SupportDraftStore store = new SupportDraftStore();
        String stale = store.put("alice", project, null);
        store.expireForTests(stale);

        store.put("alice", project, null);

        assertThat(store.sizeForTests()).isEqualTo(1);
    }

    @Test
    void aForeignTakeDoesNotBurnTheToken() {
        SupportDraftStore store = new SupportDraftStore();
        String token = store.put("alice", project, null);

        assertThat(store.take(token, "bob")).isEmpty();
        assertThat(store.take(token, "alice")).isPresent();
    }
}
