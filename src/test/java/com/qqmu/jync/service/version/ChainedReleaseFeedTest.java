package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.version.ReleaseFeed.Asset;
import com.qqmu.jync.service.version.ReleaseFeed.Release;
import com.qqmu.jync.service.version.ReleaseFeed.Source;

/**
 * The update check itself prefers GitHub (fast for most of the world) but must not leave
 * China hosts on a permanent "check failed" badge: any GitHub failure (timeout, connect
 * error, non-200) immediately falls back to the Gitee mirror. Both fail → caller surfaces
 * UNKNOWN.
 */
class ChainedReleaseFeedTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-14T04:00:00Z");

    /** Stub feed with a toggleable failure and a call counter. */
    private static final class StubFeed implements ReleaseFeed {
        private final Source source;
        private Exception failure;
        private final AtomicInteger latestCalls = new AtomicInteger();
        private final AtomicInteger tagCalls = new AtomicInteger();

        StubFeed(Source source) {
            this.source = source;
        }

        StubFeed failsWith(Exception e) {
            this.failure = e;
            return this;
        }

        int latestCalls() {
            return latestCalls.get();
        }

        int tagCalls() {
            return tagCalls.get();
        }

        @Override
        public Release latest() throws Exception {
            latestCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new Release("v1.8.0", "https://example.invalid/" + source, PUBLISHED, "body",
                    List.of(new Asset("jync.jar",
                            "https://example.invalid/" + source + "/jync.jar", 123L)), source);
        }

        @Override
        public Release byTag(String tag) throws Exception {
            tagCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new Release(tag, "https://example.invalid/" + source, PUBLISHED, "body",
                    List.of(), source);
        }
    }

    @Test
    void githubSuccessShortCircuitsAndIsTaggedGithub() throws Exception {
        StubFeed github = new StubFeed(Source.GITHUB);
        StubFeed gitee = new StubFeed(Source.GITEE);
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        Release release = chain.latest();

        assertThat(release.source()).isEqualTo(Source.GITHUB);
        assertThat(github.latestCalls()).isEqualTo(1);
        assertThat(gitee.latestCalls()).isZero();
    }

    @Test
    void githubTimeoutFallsBackToGitee() throws Exception {
        StubFeed github = new StubFeed(Source.GITHUB).failsWith(new HttpTimeoutException("slow"));
        StubFeed gitee = new StubFeed(Source.GITEE);
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        Release release = chain.latest();

        assertThat(release.source()).isEqualTo(Source.GITEE);
        assertThat(release.tag()).isEqualTo("v1.8.0");
        assertThat(release.jarAsset("jync.jar").url()).contains("GITEE");
        assertThat(github.latestCalls()).isEqualTo(1);
        assertThat(gitee.latestCalls()).isEqualTo(1);
    }

    @Test
    void githubErrorStatusAlsoFallsBack() throws Exception {
        StubFeed github = new StubFeed(Source.GITHUB)
                .failsWith(new IllegalStateException("GitHub API responded 502"));
        StubFeed gitee = new StubFeed(Source.GITEE);
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        assertThat(chain.latest().source()).isEqualTo(Source.GITEE);
    }

    @Test
    void bothFailThrowsACauseNamingBothFeeds() {
        StubFeed github = new StubFeed(Source.GITHUB).failsWith(new HttpTimeoutException("slow"));
        StubFeed gitee = new StubFeed(Source.GITEE).failsWith(new RuntimeException("offline"));
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        assertThatThrownBy(chain::latest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GITEE")
                .satisfies(e -> assertThat(e.getCause()).hasMessageContaining("offline"));
    }

    @Test
    void explicitGiteeChoiceBypassesGithub() throws Exception {
        StubFeed github = new StubFeed(Source.GITHUB)
                .failsWith(new RuntimeException("must not run"));
        StubFeed gitee = new StubFeed(Source.GITEE);
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        Release release = chain.latestFrom(Source.GITEE);

        assertThat(release.source()).isEqualTo(Source.GITEE);
        assertThat(github.latestCalls()).isZero();
        assertThat(gitee.latestCalls()).isEqualTo(1);
    }

    @Test
    void explicitSourceFailureDoesNotSilentlySwitchFeeds() {
        // The user picked a mirror in the upgrade dialog: if it fails, report it so the
        // choice (and error message) stays honest instead of downloading from elsewhere.
        StubFeed github = new StubFeed(Source.GITHUB);
        StubFeed gitee = new StubFeed(Source.GITEE).failsWith(new RuntimeException("blocked"));
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        assertThatThrownBy(() -> chain.latestFrom(Source.GITEE))
                .hasMessageContaining("blocked");
        assertThat(github.latestCalls()).isZero();
    }

    @Test
    void byTagChainsLikeLatest() throws Exception {
        StubFeed github = new StubFeed(Source.GITHUB).failsWith(new HttpTimeoutException("slow"));
        StubFeed gitee = new StubFeed(Source.GITEE);
        ChainedReleaseFeed chain = new ChainedReleaseFeed(github, gitee);

        assertThat(chain.byTag("v1.7.2").source()).isEqualTo(Source.GITEE);
        assertThat(github.tagCalls()).isEqualTo(1);
        assertThat(gitee.tagCalls()).isEqualTo(1);
    }
}
