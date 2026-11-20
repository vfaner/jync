package com.qqmu.jync.service.ai.support;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 一次助手回答的事件缓冲，供切换页面后重新接上。
 *
 * <p>SSE 流绑定在发起它的页面上：整页跳转销毁 fetch，服务端的回答却还在继续。浏览器把
 * runId 记下来，回来后先拉一份快照重放，再在运行期间轮询增量。缓冲只在内存里——它是
 * 一次对话的临时进度，不是数据；十分钟过期，单节点最多 {@value #MAX_RUNS} 个 run。
 *
 * <p>归属校验与草稿 / 确认令牌一致：只有发起账号能读自己的 run，别人的 id 一律当作不存在。
 */
@Component
public class SupportRunStore {

    /** 一次回答的可见窗口：足够覆盖最长的回答，也足够用户切回页面。 */
    public static final long TTL_MILLIS = 10 * 60 * 1000L;
    public static final int MAX_RUNS = 64;
    /** 文本之外的事件（工具进度、卡片、错误）。 */
    public static final int MAX_EVENTS_PER_RUN = 256;
    /** 正文上限；超出截断并标记，页面提示回答不完整。 */
    public static final int MAX_TEXT_CHARS = 128 * 1024;

    /** One non-delta event with the sequence number the client uses as its cursor. */
    public record RunEvent(long seq, String type, Map<String, Object> data) {
    }

    /**
     * What a reattaching page needs: the text so far, the non-delta events after its cursor,
     * and the current sequence high-water mark. {@code done} means the worker finished; the
     * page stops polling.
     */
    public record Snapshot(String id, boolean done, boolean truncated, String text,
                           List<RunEvent> events, long nextSeq) {
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final java.util.concurrent.atomic.AtomicLong created =
            new java.util.concurrent.atomic.AtomicLong();

    public String create(String username) {
        long now = System.currentTimeMillis();
        runs.values().removeIf(run -> run.isExpired(now));
        if (runs.size() >= MAX_RUNS) {
            evictOldest();
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        // Creation order comes from a counter, not the clock: runs created in the same
        // millisecond must still evict deterministically (oldest first).
        runs.put(id, new Run(username, now, created.incrementAndGet()));
        return id;
    }

    /** Records one event and returns its sequence number; an unknown run is a no-op. */
    public long append(String runId, String type, Map<String, Object> data) {
        Run run = runId == null ? null : runs.get(runId);
        return run == null ? 0L : run.append(type, data);
    }

    /** The worker is finished: the page can stop polling and keep what it has. */
    public void complete(String runId) {
        Run run = runId == null ? null : runs.get(runId);
        if (run != null) {
            run.complete();
        }
    }

    /**
     * The owner's view of a run, or empty for an unknown, expired or foreign id. The caller
     * maps empty to 404 without distinguishing the three — a token must not confirm that
     * another account's run exists.
     */
    public Optional<Snapshot> snapshot(String runId, String username, long sinceSeq) {
        if (runId == null || username == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Run run = runs.get(runId);
        if (run == null || run.isExpired(now) || !username.equals(run.owner())) {
            return Optional.empty();
        }
        return Optional.of(run.snapshot(runId, Math.max(0L, sinceSeq)));
    }

    private void evictOldest() {
        String oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            long order = entry.getValue().createdOrder();
            if (order < oldestAt) {
                oldestAt = order;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) {
            runs.remove(oldest);
        }
    }

    /** Test seam: how many runs are held (expired ones included until the next sweep). */
    int sizeForTests() {
        return runs.size();
    }

    /** Test seam: move one run past its expiry without sleeping. */
    void expireForTests(String runId) {
        Run run = runs.get(runId);
        if (run != null) {
            run.expireForTests();
        }
    }

    private static final class Run {
        private final String owner;
        private final long createdOrder;
        private final StringBuilder text = new StringBuilder();
        private final List<RunEvent> events = new ArrayList<>();
        private long seq;
        private boolean done;
        private boolean truncated;
        private long expiresAt;

        Run(String owner, long now, long createdOrder) {
            this.owner = owner;
            this.createdOrder = createdOrder;
            this.expiresAt = now + TTL_MILLIS;
        }

        String owner() {
            return owner;
        }

        long createdOrder() {
            return createdOrder;
        }

        synchronized boolean isExpired(long now) {
            return expiresAt < now;
        }

        synchronized void expireForTests() {
            expiresAt = System.currentTimeMillis() - 1;
        }

        /**
         * Deltas are folded into the text instead of being stored one object per chunk: a
         * 3000-character answer can arrive as thousands of chunks, and the page only needs
         * the cumulative text.
         */
        synchronized long append(String type, Map<String, Object> data) {
            long eventSeq = ++seq;
            expiresAt = System.currentTimeMillis() + TTL_MILLIS;
            if ("delta".equals(type)) {
                Object raw = data == null ? null : data.get("text");
                String piece = raw == null ? "" : String.valueOf(raw);
                int room = MAX_TEXT_CHARS - text.length();
                if (piece.length() > room) {
                    if (room > 0) {
                        text.append(piece, 0, room);
                    }
                    truncated = true;
                } else {
                    text.append(piece);
                }
            } else if (events.size() < MAX_EVENTS_PER_RUN) {
                events.add(new RunEvent(eventSeq, type,
                        data == null ? Map.of() : new LinkedHashMap<>(data)));
            } else {
                truncated = true;
            }
            return eventSeq;
        }

        synchronized void complete() {
            done = true;
            expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        }

        synchronized Snapshot snapshot(String id, long sinceSeq) {
            List<RunEvent> newer = new ArrayList<>();
            for (RunEvent event : events) {
                if (event.seq() > sinceSeq) {
                    newer.add(event);
                }
            }
            return new Snapshot(id, done, truncated, text.toString(), List.copyOf(newer), seq);
        }
    }
}
