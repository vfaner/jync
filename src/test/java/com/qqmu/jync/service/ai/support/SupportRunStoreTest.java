package com.qqmu.jync.service.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 切换页面会销毁 SSE 流，浏览器必须能凭 runId 重新拿到已经发出的事件。存储是有界的：
 * 文本与事件都截断，run 有 TTL 与总数上限，归属校验保证别人的 run 读不到。
 */
class SupportRunStoreTest {

    private final SupportRunStore store = new SupportRunStore();

    @Test
    void deltaTextAccumulatesAndOtherEventsKeepTheirSequence() {
        String id = store.create("alice");
        store.append(id, "delta", Map.of("text", "你好"));
        store.append(id, "tool", Map.of("id", "c1", "state", "RUNNING"));
        store.append(id, "delta", Map.of("text", "世界"));

        SupportRunStore.Snapshot snapshot = store.snapshot(id, "alice", 0).orElseThrow();

        assertThat(snapshot.text()).isEqualTo("你好世界");
        assertThat(snapshot.events()).extracting(SupportRunStore.RunEvent::seq)
                .containsExactly(2L);
        assertThat(snapshot.events().get(0).type()).isEqualTo("tool");
        assertThat(snapshot.events().get(0).data()).containsEntry("state", "RUNNING");
        assertThat(snapshot.nextSeq()).isEqualTo(3L);
        assertThat(snapshot.done()).isFalse();
    }

    @Test
    void snapshotSinceACursorReturnsOnlyNewerEvents() {
        String id = store.create("alice");
        store.append(id, "tool", Map.of("id", "a"));
        store.append(id, "delta", Map.of("text", "x"));
        long cursor = store.append(id, "tool", Map.of("id", "b"));
        store.append(id, "tool", Map.of("id", "c"));

        SupportRunStore.Snapshot snapshot = store.snapshot(id, "alice", cursor).orElseThrow();

        assertThat(snapshot.events()).extracting(event -> event.data().get("id"))
                .containsExactly("c");
    }

    @Test
    void aForeignUserOrUnknownRunGetsNothing() {
        String id = store.create("alice");
        store.append(id, "delta", Map.of("text", "secret"));

        assertThat(store.snapshot(id, "bob", 0)).isEmpty();
        assertThat(store.snapshot("nope", "alice", 0)).isEmpty();
        assertThat(store.snapshot(id, null, 0)).isEmpty();
    }

    @Test
    void completeMarksTheRunDoneAndIsIdempotent() {
        String id = store.create("alice");
        store.complete(id);
        store.complete(id);

        SupportRunStore.Snapshot snapshot = store.snapshot(id, "alice", 0).orElseThrow();

        assertThat(snapshot.done()).isTrue();
        assertThat(snapshot.events()).isEmpty();
    }

    @Test
    void anExpiredRunIsSweptAndGone() {
        String id = store.create("alice");
        store.append(id, "delta", Map.of("text", "x"));
        store.expireForTests(id);

        assertThat(store.snapshot(id, "alice", 0)).isEmpty();

        store.create("alice");
        assertThat(store.sizeForTests()).isEqualTo(1);
    }

    @Test
    void textAndEventCapsTruncateInsteadOfGrowingWithoutBound() {
        String id = store.create("alice");
        String chunk = "x".repeat(SupportRunStore.MAX_TEXT_CHARS / 2 + 10);
        store.append(id, "delta", Map.of("text", chunk));
        store.append(id, "delta", Map.of("text", chunk));
        for (int i = 0; i < SupportRunStore.MAX_EVENTS_PER_RUN + 5; i++) {
            store.append(id, "tool", Map.of("id", "c" + i));
        }

        SupportRunStore.Snapshot snapshot = store.snapshot(id, "alice", 0).orElseThrow();

        assertThat(snapshot.text()).hasSize(SupportRunStore.MAX_TEXT_CHARS);
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.events()).hasSize(SupportRunStore.MAX_EVENTS_PER_RUN);
    }

    @Test
    void beyondTheRunCapTheOldestRunsAreDropped() {
        String first = store.create("alice");
        for (int i = 1; i < SupportRunStore.MAX_RUNS; i++) {
            store.create("alice");
        }
        String last = store.create("alice");

        assertThat(store.sizeForTests()).isEqualTo(SupportRunStore.MAX_RUNS);
        assertThat(store.snapshot(first, "alice", 0)).isEmpty();
        assertThat(store.snapshot(last, "alice", 0)).isPresent();
    }
}
