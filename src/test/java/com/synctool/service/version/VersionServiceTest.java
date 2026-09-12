package com.synctool.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Version comparison, remote-state mapping, caching and release-notes rendering.
 * No Spring context and no network: the {@link ReleaseFeed} is an in-memory fake.
 */
class VersionServiceTest {

    private static final Instant PUBLISHED = Instant.parse("2026-09-10T08:30:00Z");

    /** Configurable feed counting every call so cache behavior is verifiable. */
    private static final class FakeFeed implements ReleaseFeed {
        private ReleaseFeed.Release latest;
        private Exception latestFailure;
        private ReleaseFeed.Release tagRelease;
        private final AtomicInteger latestCalls = new AtomicInteger();
        private final AtomicInteger byTagCalls = new AtomicInteger();

        @Override
        public ReleaseFeed.Release latest() throws Exception {
            latestCalls.incrementAndGet();
            if (latestFailure != null) {
                throw latestFailure;
            }
            return latest;
        }

        @Override
        public ReleaseFeed.Release byTag(String tag) {
            byTagCalls.incrementAndGet();
            return tagRelease;
        }
    }

    private static ReleaseFeed.Release release(String tag, String url, String body) {
        return new ReleaseFeed.Release(tag, url, PUBLISHED, body);
    }

    private static VersionService service(String current, ReleaseFeed feed, boolean enabled) {
        return new VersionService(() -> current, feed, enabled);
    }

    @Test
    void sameVersionAsLatestIsUpToDate() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "https://github.com/o/r/releases/tag/v1.1.1", "body");
        VersionInfo info = service("1.1.1", feed, true).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
        assertThat(info.getCurrentVersion()).isEqualTo("1.1.1");
        assertThat(info.getLatestVersion()).isEqualTo("1.1.1");
        assertThat(info.getLatestTag()).isEqualTo("v1.1.1");
        assertThat(info.getReleaseUrl()).isEqualTo("https://github.com/o/r/releases/tag/v1.1.1");
        assertThat(info.getLatestPublishedDate()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void newerReleaseMeansUpdateAvailable() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.2.0", "https://github.com/o/r/releases/tag/v1.2.0", "body");
        VersionInfo info = service("1.1.1", feed, true).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UPDATE_AVAILABLE);
        assertThat(info.getLatestVersion()).isEqualTo("1.2.0");
        assertThat(info.getReleaseUrl()).endsWith("/v1.2.0");
    }

    @Test
    void unknownCurrentVersionStaysUnknown() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "u", "b");
        VersionInfo info = service(null, feed, true).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UNKNOWN);
        assertThat(info.getCurrentVersion()).isNull();
        assertThat(info.getLatestVersion()).isEqualTo("1.1.1");
    }

    @Test
    void failedFeedIsCachedAsUnknownInsteadOfHittingGitHubEveryPageView() {
        FakeFeed feed = new FakeFeed();
        feed.latestFailure = new RuntimeException("offline");
        VersionService versionService = service("1.1.1", feed, true);

        assertThat(versionService.snapshot().getState()).isEqualTo(VersionInfo.State.UNKNOWN);
        assertThat(versionService.snapshot().getState()).isEqualTo(VersionInfo.State.UNKNOWN);
        // Failure results are cached (1 minute): the second page view must not call out again.
        assertThat(feed.latestCalls).hasValue(1);
    }

    @Test
    void firstSnapshotPaintsCheckingInsteadOfBlockingOnTheNetwork() {
        // The refresh task is queued but not run yet: on an offline host the page must paint
        // immediately with a CHECKING badge instead of waiting out the HTTP timeout.
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "u", "b");
        List<Runnable> queued = new ArrayList<>();
        VersionService versionService = new VersionService(() -> "1.1.1", feed, true, queued::add);

        VersionInfo first = versionService.snapshot();
        assertThat(first.getState()).isEqualTo(VersionInfo.State.CHECKING);
        assertThat(feed.latestCalls).hasValue(0);

        queued.get(0).run();
        assertThat(versionService.snapshot().getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
        assertThat(feed.latestCalls).hasValue(1);
    }

    @Test
    void onlyOneRefreshIsInFlightAtATime() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "u", "b");
        AtomicInteger queuedTasks = new AtomicInteger();
        VersionService versionService = new VersionService(
                () -> "1.1.1", feed, true, task -> queuedTasks.incrementAndGet());

        versionService.warmUp();
        versionService.snapshot();
        assertThat(queuedTasks).hasValue(1);
    }

    @Test
    void successfulResultIsCached() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "u", "b");
        VersionService versionService = service("1.1.1", feed, true);

        versionService.snapshot();
        versionService.snapshot();
        assertThat(feed.latestCalls).hasValue(1);
    }

    @Test
    void disabledFlagSkipsTheNetworkButStillAttachesNotes() {
        FakeFeed feed = new FakeFeed();
        feed.latestFailure = new RuntimeException("must never be called");
        feed.tagRelease = release("v1.1.1", "u", "# Notes");
        VersionInfo info = service("1.1.1", feed, false).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.DISABLED);
        assertThat(feed.latestCalls).hasValue(0);
        // Release notes come from the classpath/feed, not the /latest call, so they survive.
        assertThat(info.getCurrentNotesHtml()).contains("<h1>");
    }

    @Test
    void releaseNotesFallBackToTaggedReleaseAndAreHtmlEscaped() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v9.9.9", "u", "latest-body");
        // 9.9.9 has no bundled notes file, so the tagged release body must be used.
        feed.tagRelease = release("v9.9.9", "u", "# Title\n\n<script>alert(1)</script>");
        VersionInfo info = service("9.9.9", feed, true).snapshot();

        assertThat(info.getCurrentNotesHtml()).contains("<h1>Title</h1>");
        assertThat(info.getCurrentNotesHtml()).contains("&lt;script&gt;");
        assertThat(info.getCurrentNotesHtml()).doesNotContain("<script>");
    }

    @Test
    void missingNotesEverywhereYieldNullRatherThanBreakingThePage() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v9.9.9", "u", "b");
        feed.tagRelease = null; // byTag returns null -> NPE caught like any feed failure
        VersionInfo info = service("9.9.9", feed, true).snapshot();

        assertThat(info.getCurrentNotesHtml()).isNull();
    }

    @Test
    void compareVersionsHandlesMultiDigitSegmentsAndVPrefix() {
        assertThat(VersionService.compareVersions("1.2.10", "1.2.9")).isPositive();
        assertThat(VersionService.compareVersions("2.0.0", "1.9.9")).isPositive();
        assertThat(VersionService.compareVersions("v1.2", "1.2.0")).isZero();
        assertThat(VersionService.compareVersions("V1.1.1", "1.1.1")).isZero();
        assertThat(VersionService.compareVersions("1.1.1", "1.2.0")).isNegative();
        assertThat(VersionService.stripV(" v1.2 ")).isEqualTo("1.2");
        assertThat(VersionService.stripV(null)).isEmpty();
    }
}
