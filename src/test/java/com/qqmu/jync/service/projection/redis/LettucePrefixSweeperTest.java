package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.RedisTarget;

/**
 * The prefix sweep: SCAN with a MATCH, then UNLINK the page. The {@link
 * RedisPrefixSweeper.Handle} seam has no other command, so there is no code path that could
 * reach {@code KEYS}, {@code FLUSHDB} or {@code FLUSHALL} — deleting another project's keys or
 * the whole database is impossible by construction, not by convention.
 */
class LettucePrefixSweeperTest {

    @Test
    void sweepUsesScanAndUnlinkOnly() {
        FakeHandle handle = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("7", List.of("jync:1:a", "jync:1:b")),
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:c"))));

        long swept = new LettucePrefixSweeper(null, target -> List.of(handle))
                .sweep(target(), "jync:1:");

        // Nothing is deleted until a full page is known, and the pattern is always the prefix.
        assertThat(handle.scans).containsExactly("jync:1:*", "jync:1:*");
        assertThat(handle.unlinked).containsExactly("jync:1:a", "jync:1:b", "jync:1:c");
        assertThat(swept).isEqualTo(3);
        assertThat(handle.closed).isTrue();
    }

    @Test
    void thePrefixIsNormalisedToItsNamespaceBoundary() {
        FakeHandle handle = new FakeHandle(List.of(new RedisPrefixSweeper.ScanPage("0", List.of())));

        new LettucePrefixSweeper(null, target -> List.of(handle)).sweep(target(), "jync:1");

        // Without the trailing ':' the pattern would also match project 10, 11, ...
        assertThat(handle.scans).containsExactly("jync:1:*");
    }

    @Test
    void everyClusterMasterIsSwept() {
        FakeHandle first = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:a"))));
        FakeHandle second = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:b"))));

        long swept = new LettucePrefixSweeper(null, target -> List.of(first, second))
                .sweep(target(), "jync:1:");

        assertThat(first.unlinked).containsExactly("jync:1:a");
        assertThat(second.unlinked).containsExactly("jync:1:b");
        assertThat(swept).isEqualTo(2);
    }

    @Test
    void aClusterSweepClosesNothingUntilEveryMasterIsScanned() {
        // The real connector pins one cluster connection and shares one closer across every
        // per-master handle: closing any handle shuts the client down. Closing a handle inside
        // the sweep loop therefore kills the connection before the second master is read — the
        // rebuild aborts with the CDC position already dropped and half the keys left behind.
        SharedCloser shared = new SharedCloser();
        FakeHandle first = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:a"))), shared);
        FakeHandle second = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:b"))), shared);

        long swept = new LettucePrefixSweeper(null, target -> List.of(first, second))
                .sweep(target(), "jync:1:");

        assertThat(swept).isEqualTo(2);
        assertThat(second.unlinked).containsExactly("jync:1:b");
        assertThat(first.closed).isTrue();
        assertThat(second.closed).isTrue();
    }

    @Test
    void anEmptyPrefixIsRefusedBeforeAnyCommand() {
        boolean[] connected = {false};

        assertThatThrownBy(() -> new LettucePrefixSweeper(null, target -> {
            connected[0] = true;
            return List.of();
        }).sweep(target(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
        assertThat(connected[0]).isFalse();
    }

    @Test
    void deletingInBatchesKeepsOneScanPageFromBecomingOneHugeCommand() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 1_201; i++) {
            keys.add("jync:1:" + i);
        }
        FakeHandle handle = new FakeHandle(List.of(new RedisPrefixSweeper.ScanPage("0", keys)));

        new LettucePrefixSweeper(null, target -> List.of(handle)).sweep(target(), "jync:1:");

        assertThat(handle.unlinkCalls).hasSize(3);
        assertThat(handle.unlinkCalls).allSatisfy(batch ->
                assertThat(batch.size()).isLessThanOrEqualTo(500));
    }

    @Test
    void aFailedUnlinkClosesTheHandleAndPropagates() {
        FakeHandle handle = new FakeHandle(List.of(
                new RedisPrefixSweeper.ScanPage("0", List.of("jync:1:a"))));
        handle.failUnlink = true;

        assertThatThrownBy(() -> new LettucePrefixSweeper(null, target -> List.of(handle))
                .sweep(target(), "jync:1:"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unlink");
        assertThat(handle.closed).isTrue();
    }

    // —— helpers ——

    private static RedisTarget target() {
        RedisTarget target = new RedisTarget();
        target.setId(9L);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        return target;
    }

    /** The shared client behind every cluster handle: one close shuts all of them down. */
    private static final class SharedCloser {

        private boolean closed;

        private void check() {
            if (closed) {
                throw new IllegalStateException("client is shut down");
            }
        }

        private void close() {
            closed = true;
        }
    }

    private static final class FakeHandle implements RedisPrefixSweeper.Handle {

        private final List<RedisPrefixSweeper.ScanPage> pages;
        private final SharedCloser shared;
        private int next;
        private final List<String> scans = new ArrayList<>();
        private final List<String> unlinked = new ArrayList<>();
        private final List<List<String>> unlinkCalls = new ArrayList<>();
        private boolean failUnlink;
        private boolean closed;

        private FakeHandle(List<RedisPrefixSweeper.ScanPage> pages) {
            this(pages, null);
        }

        private FakeHandle(List<RedisPrefixSweeper.ScanPage> pages, SharedCloser shared) {
            this.pages = pages;
            this.shared = shared;
        }

        @Override
        public RedisPrefixSweeper.ScanPage scan(String pattern) {
            if (shared != null) {
                shared.check();
            }
            scans.add(pattern);
            return pages.get(next++);
        }

        @Override
        public long unlink(List<String> keys) {
            if (shared != null) {
                shared.check();
            }
            if (failUnlink) {
                throw new IllegalStateException("unlink refused");
            }
            unlinkCalls.add(List.copyOf(keys));
            unlinked.addAll(keys);
            return keys.size();
        }

        @Override
        public void close() {
            closed = true;
            if (shared != null) {
                shared.close();
            }
        }
    }
}
