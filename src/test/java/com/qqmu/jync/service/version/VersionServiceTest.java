package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.version.ReleaseFeed.Source;

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

    /**
     * A feed that answers per mirror and records the order it was asked. The click-triggered
     * check must ask Gitee first and only fall back to GitHub; the passive {@code latest()}
     * path is deliberately unsupported here so an accidental call fails loudly.
     */
    private static final class SourceAwareFakeFeed implements ReleaseFeed, SourceAwareFeed {
        private final List<Source> calls = new ArrayList<>();
        private final Map<Source, ReleaseFeed.Release> releases = new EnumMap<>(Source.class);
        private final Map<Source, Exception> failures = new EnumMap<>(Source.class);

        SourceAwareFakeFeed answer(Source source, ReleaseFeed.Release release) {
            releases.put(source, release);
            return this;
        }

        SourceAwareFakeFeed fail(Source source, Exception failure) {
            failures.put(source, failure);
            return this;
        }

        @Override
        public ReleaseFeed.Release latest() {
            throw new AssertionError("the click check must ask a specific mirror");
        }

        @Override
        public ReleaseFeed.Release byTag(String tag) {
            return null;
        }

        @Override
        public ReleaseFeed.Release latestFrom(Source source) throws Exception {
            calls.add(source);
            Exception failure = failures.get(source);
            if (failure != null) {
                throw failure;
            }
            return releases.get(source);
        }
    }

    /** Answers "same bytes / different bytes / cannot tell" and records what it was asked. */
    private static final class FakeArtifactComparison implements ArtifactComparison {
        private ArtifactComparison.Result result = ArtifactComparison.Result.UNKNOWN;
        private final AtomicInteger calls = new AtomicInteger();
        private ReleaseFeed.Release asked;

        FakeArtifactComparison answer(ArtifactComparison.Result answered) {
            this.result = answered;
            return this;
        }

        @Override
        public ArtifactComparison.Result compare(ReleaseFeed.Release release) {
            calls.incrementAndGet();
            asked = release;
            return result;
        }
    }

    /** Records what an admin dismissed, in memory; the JPA-backed one is tested separately. */
    private static final class FakeDismissal implements ArtifactDismissal {
        private String key;
        private final AtomicInteger dismissals = new AtomicInteger();

        @Override
        public String dismissedKey() {
            return key;
        }

        @Override
        public void dismiss(String newKey) {
            key = newKey;
            dismissals.incrementAndGet();
        }
    }

    private static ReleaseFeed.Release release(String tag, String url, String body) {
        return new ReleaseFeed.Release(tag, url, PUBLISHED, body);
    }

    /** A release carrying the fixed-name jar asset, as both real feeds deliver it. */
    private static ReleaseFeed.Release releaseWithJar(String tag, String sha256, Instant uploaded) {
        return new ReleaseFeed.Release(tag, "https://github.com/o/r/releases/tag/" + tag,
                PUBLISHED, "body",
                List.of(new ReleaseFeed.Asset(ReleaseFeed.JAR_ASSET_NAME,
                        "https://github.com/o/r/releases/download/" + tag + "/jync.jar",
                        83_502_549L, sha256, uploaded)),
                ReleaseFeed.Source.GITHUB);
    }

    private static VersionService service(String current, ReleaseFeed feed, boolean enabled) {
        return new VersionService(() -> current, feed, enabled);
    }

    private static VersionService serviceWithComparison(String current, ReleaseFeed feed,
                                                        ArtifactComparison comparison) {
        return new VersionService(() -> current, feed, comparison, true);
    }

    private static VersionService serviceWithComparison(String current, ReleaseFeed feed,
                                                        ArtifactComparison comparison,
                                                        ArtifactDismissal dismissal) {
        return new VersionService(() -> current, feed, comparison, dismissal, true);
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

    /**
     * v1.8.0 was re-uploaded three times under the same tag while the number stayed put, so
     * "same version" is not "same bytes". When the artifact provably changed, the card must say
     * so and offer the upgrade — otherwise hosts on the older upload never learn about it.
     */
    @Test
    void aRePublishedArtifactUnderTheSameVersionIsReportedAsArtifactUpdated() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "ab".repeat(32),
                Instant.parse("2026-10-25T01:07:45Z"));
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);

        VersionInfo info = serviceWithComparison("1.8.0", feed, comparison).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.ARTIFACT_UPDATED);
        assertThat(info.getLatestVersion()).isEqualTo("1.8.0");
        // The asset's own upload time, not the release's publish date: only the former moves
        // when a jar is replaced under an existing tag.
        assertThat(info.getLatestArtifactDate()).isEqualTo("2026-10-25");
        assertThat(comparison.calls).hasValue(1);
        assertThat(comparison.asked).isSameAs(feed.latest);
    }

    @Test
    void theSameBytesUnderTheSameVersionStayUpToDate() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "ab".repeat(32), PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.SAME);

        VersionInfo info = serviceWithComparison("1.8.0", feed, comparison).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
    }

    @Test
    void anUnanswerableArtifactComparisonStaysUpToDate() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", null, null);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.UNKNOWN);

        VersionInfo info = serviceWithComparison("1.8.0", feed, comparison).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
    }

    @Test
    void aNewerVersionNeverSpendsAProbeOnTheArtifact() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.2.0", "ab".repeat(32), PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);

        VersionInfo info = serviceWithComparison("1.1.1", feed, comparison).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UPDATE_AVAILABLE);
        assertThat(comparison.calls).hasValue(0);
    }

    /**
     * A jar the admin already dismissed (local rebuild, mirror lag) must not keep nagging —
     * otherwise the badge is a permanent decoration and stops meaning anything.
     */
    @Test
    void aDismissedArtifactIsNotReportedAgain() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "ab".repeat(32), PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);
        FakeDismissal dismissal = new FakeDismissal();
        dismissal.dismiss("sha256:" + "ab".repeat(32));

        VersionInfo info = serviceWithComparison("1.8.0", feed, comparison, dismissal).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
    }

    /** The next upload changes the digest, so the old dismissal must not keep it quiet. */
    @Test
    void aNewUploadIsReportedAgainEvenAfterADismissal() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "cd".repeat(32), PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);
        FakeDismissal dismissal = new FakeDismissal();
        dismissal.dismiss("sha256:" + "ab".repeat(32));

        VersionInfo info = serviceWithComparison("1.8.0", feed, comparison, dismissal).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.ARTIFACT_UPDATED);
    }

    /** Dismissing stores the published bytes' identity, not the running jar's. */
    @Test
    void dismissArtifactRecordsThePublishedIdentity() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "ab".repeat(32), PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);
        FakeDismissal dismissal = new FakeDismissal();
        VersionService versionService =
                serviceWithComparison("1.8.0", feed, comparison, dismissal);
        assertThat(versionService.snapshot().getState())
                .isEqualTo(VersionInfo.State.ARTIFACT_UPDATED);

        assertThat(versionService.dismissArtifact()).isTrue();

        assertThat(dismissal.dismissals).hasValue(1);
        assertThat(dismissal.key).isEqualTo("sha256:" + "ab".repeat(32));
        // The page reloads right after dismissing: the cache must already answer quietly.
        assertThat(versionService.snapshot().getState()).isEqualTo(VersionInfo.State.UP_TO_DATE);
    }

    /** A feed without a digest still yields a stable identity, so Gitee can be dismissed too. */
    @Test
    void withoutADigestTheDismissalIdentityIsTheFileSize() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", null, PUBLISHED);
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);
        FakeDismissal dismissal = new FakeDismissal();
        VersionService versionService =
                serviceWithComparison("1.8.0", feed, comparison, dismissal);
        versionService.snapshot();

        versionService.dismissArtifact();

        assertThat(dismissal.key).isEqualTo("size:83502549");
    }

    @Test
    void dismissArtifactWithoutAPublishedJarReportsNothingToDismiss() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "u", "b");
        FakeDismissal dismissal = new FakeDismissal();
        VersionService versionService =
                serviceWithComparison("1.1.1", feed, new FakeArtifactComparison(), dismissal);
        versionService.snapshot();

        assertThat(versionService.dismissArtifact()).isFalse();
        assertThat(dismissal.dismissals).hasValue(0);
    }

    /** The nav dot asks the same question; a dismissal must silence it as well. */
    @Test
    void clickCheckStaysQuietAfterADismissal() {
        FakeFeed feed = new FakeFeed();
        feed.latest = releaseWithJar("v1.8.0", "ab".repeat(32), PUBLISHED);
        FakeDismissal dismissal = new FakeDismissal();
        dismissal.dismiss("sha256:" + "ab".repeat(32));
        VersionService versionService = serviceWithComparison("1.8.0", feed,
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT),
                dismissal);

        var check = versionService.checkNow();

        assertThat(check.updateAvailable()).isFalse();
        assertThat(check.artifactUpdated()).isFalse();
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
    void aPageRenderNeverWaitsForAnInFlightUpdateCheck() throws Exception {
        // The boot warm-up holds the service's monitor for the whole network round trip.
        // The settings page renders the release notes through what used to be the same
        // monitor, so its first paint blocked until GitHub and Gitee had both timed out —
        // exactly on the offline-ish hosts this feature is written for.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        ReleaseFeed blocking = new ReleaseFeed() {
            @Override
            public ReleaseFeed.Release latest() throws Exception {
                entered.countDown();
                unblock.await(10, TimeUnit.SECONDS);
                return release("v1.1.1", "https://github.com/o/r", "body");
            }

            @Override
            public ReleaseFeed.Release byTag(String tag) {
                return null;
            }
        };
        VersionService versionService =
                new VersionService(() -> "1.1.1", blocking, true, Runnable::run);
        Thread refresher = new Thread(versionService::warmUp, "version-warm-up");
        refresher.setDaemon(true);
        refresher.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        ExecutorService page = Executors.newSingleThreadExecutor();
        try {
            Future<VersionInfo> render = page.submit(versionService::snapshot);
            long started = System.nanoTime();
            VersionInfo info = render.get(2, TimeUnit.SECONDS);
            assertThat(info.getState()).isEqualTo(VersionInfo.State.CHECKING);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            unblock.countDown();
            page.shutdownNow();
            refresher.join(5_000);
        }
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

    /** Date-stamped segments overflow an int; the comparison must still order them. */
    @Test
    void compareVersionsHandlesSegmentsBeyondIntRange() {
        assertThat(VersionService.compareVersions("20250923120000", "20240101000000")).isPositive();
        assertThat(VersionService.compareVersions("v20250923120000.1", "20250923120000.0")).isPositive();
        assertThat(VersionService.compareVersions("20250923120000", "20250923120000")).isZero();
    }

    /**
     * The settings page's own update check: GitHub first — it is the canonical feed, and its
     * API is the only one that reports download counts — Gitee second as the domestic
     * fallback. The badge only needs to know whether a newer release exists, so the
     * comparison is the same one the passive snapshot uses.
     */
    @Test
    void clickCheckAsksGithubFirstAndSkipsGiteeOnceGithubAnswers() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed().answer(Source.GITHUB,
                release("v1.7.10", "https://github.com/vfaner/jync/releases/tag/v1.7.10", ""));
        VersionService.UpdateCheck check =
                new VersionService(() -> "1.7.9", feed, feed, true).checkNow();

        assertThat(check.checked()).isTrue();
        assertThat(check.updateAvailable()).isTrue();
        assertThat(check.currentVersion()).isEqualTo("1.7.9");
        assertThat(check.latestVersion()).isEqualTo("1.7.10");
        assertThat(check.latestTag()).isEqualTo("v1.7.10");
        assertThat(check.releaseUrl())
                .isEqualTo("https://github.com/vfaner/jync/releases/tag/v1.7.10");
        assertThat(feed.calls).containsExactly(Source.GITHUB);
    }

    /**
     * The nav badge is driven by the click-triggered check, so it has to light up for a
     * re-published artifact too — that is the only signal a host on the older v1.8.0 upload
     * will ever get. {@code artifactUpdated} tells the badge to word it differently instead
     * of announcing "new version v1.8.0" to someone already on v1.8.0.
     */
    @Test
    void clickCheckReportsARePublishedArtifactAsAnUpdate() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed().answer(Source.GITHUB,
                releaseWithJar("v1.8.0", "ab".repeat(32), Instant.parse("2026-10-25T01:07:45Z")));
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.DIFFERENT);

        VersionService.UpdateCheck check =
                new VersionService(() -> "1.8.0", feed, feed, comparison, true).checkNow();

        assertThat(check.checked()).isTrue();
        assertThat(check.updateAvailable()).isTrue();
        assertThat(check.artifactUpdated()).isTrue();
        assertThat(check.latestVersion()).isEqualTo("1.8.0");
    }

    @Test
    void clickCheckStaysQuietWhenTheSameVersionStillHasTheSameBytes() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed().answer(Source.GITHUB,
                releaseWithJar("v1.8.0", "ab".repeat(32), PUBLISHED));
        FakeArtifactComparison comparison =
                new FakeArtifactComparison().answer(ArtifactComparison.Result.SAME);

        VersionService.UpdateCheck check =
                new VersionService(() -> "1.8.0", feed, feed, comparison, true).checkNow();

        assertThat(check.updateAvailable()).isFalse();
        assertThat(check.artifactUpdated()).isFalse();
    }

    @Test
    void clickCheckFallsBackToGiteeWhenGithubFails() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed()
                .fail(Source.GITHUB, new HttpTimeoutException("github slow"))
                .answer(Source.GITEE,
                        release("v1.7.10",
                                "https://gitee.com/super_rgh/jync/releases/tag/v1.7.10", ""));
        VersionService.UpdateCheck check =
                new VersionService(() -> "1.7.9", feed, feed, true).checkNow();

        assertThat(check.checked()).isTrue();
        assertThat(check.updateAvailable()).isTrue();
        assertThat(check.latestVersion()).isEqualTo("1.7.10");
        assertThat(feed.calls).containsExactly(Source.GITHUB, Source.GITEE);
    }

    @Test
    void clickCheckStaysSilentWhenBothMirrorsFail() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed()
                .fail(Source.GITHUB, new HttpTimeoutException("github slow"))
                .fail(Source.GITEE, new HttpTimeoutException("gitee slow"));
        VersionService.UpdateCheck check =
                new VersionService(() -> "1.7.9", feed, feed, true).checkNow();

        assertThat(check.checked()).isFalse();
        assertThat(check.updateAvailable()).isFalse();
        assertThat(check.latestVersion()).isNull();
        assertThat(feed.calls).containsExactly(Source.GITHUB, Source.GITEE);
    }

    @Test
    void clickCheckDoesNotFlagTheRunningVersionAsAnUpdate() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed()
                .answer(Source.GITHUB, release("v1.7.9", "https://github.com/o/r/releases/tag/v1.7.9", ""));
        VersionService.UpdateCheck check =
                new VersionService(() -> "1.7.9", feed, feed, true).checkNow();

        assertThat(check.checked()).isTrue();
        assertThat(check.updateAvailable()).isFalse();
        assertThat(check.latestVersion()).isEqualTo("1.7.9");
    }

    @Test
    void clickCheckMakesNoNetworkCallWhenTheFeatureIsDisabled() {
        SourceAwareFakeFeed feed = new SourceAwareFakeFeed()
                .answer(Source.GITHUB, release("v1.7.10", "https://github.com/o/r/releases/tag/v1.7.10", ""));
        VersionService.UpdateCheck check =
                new VersionService(() -> "1.7.9", feed, feed, false).checkNow();

        assertThat(check.checked()).isFalse();
        assertThat(check.updateAvailable()).isFalse();
        assertThat(feed.calls).isEmpty();
    }

    /**
     * The URL arrives in feed JSON and is rendered into th:href, so a javascript: target or
     * an http downgrade must never reach the page — the link is dropped instead. Both
     * hosting platforms are trusted now (the check falls back from GitHub to Gitee).
     */
    @Test
    void onlyHttpsGithubOrGiteeUrlsAreTrustedAsReleaseLinks() {
        assertThat(VersionService.trustedReleaseUrl("https://github.com/o/r/releases/tag/v1"))
                .isEqualTo("https://github.com/o/r/releases/tag/v1");
        assertThat(VersionService
                .trustedReleaseUrl("https://gitee.com/o/r/releases/tag/v1"))
                .isEqualTo("https://gitee.com/o/r/releases/tag/v1");
        assertThat(VersionService.trustedReleaseUrl("javascript:alert(1)")).isNull();
        assertThat(VersionService.trustedReleaseUrl("http://github.com/o/r")).isNull();
        assertThat(VersionService.trustedReleaseUrl("http://gitee.com/o/r")).isNull();
        assertThat(VersionService.trustedReleaseUrl("https://evil.example/github.com/")).isNull();
        assertThat(VersionService.trustedReleaseUrl(null)).isNull();

        FakeFeed feed = new FakeFeed();
        feed.latest = release("v1.1.1", "javascript:alert(1)", "b");
        assertThat(service("1.1.1", feed, true).snapshot().getReleaseUrl()).isNull();
    }

    @Test
    void snapshotCarriesTheFeedSourceSoThePageCanShowWhichMirrorAnswered() {
        FakeFeed feed = new FakeFeed();
        feed.latest = new ReleaseFeed.Release("v1.8.0",
                "https://gitee.com/super_rgh/jync/releases/tag/v1.8.0", PUBLISHED, "body",
                List.of(), ReleaseFeed.Source.GITEE);
        VersionInfo info = service("1.7.2", feed, true).snapshot();

        assertThat(info.getState()).isEqualTo(VersionInfo.State.UPDATE_AVAILABLE);
        assertThat(info.getLatestSource()).isEqualTo(ReleaseFeed.Source.GITEE);
        assertThat(info.getReleaseUrl()).startsWith("https://gitee.com/");
    }

    /** Notes may come from the GitHub release body; a javascript: link must be defused. */
    @Test
    void unsafeLinkProtocolsInReleaseNotesAreSanitized() {
        FakeFeed feed = new FakeFeed();
        feed.latest = release("v9.9.9", "u", "latest-body");
        feed.tagRelease = release("v9.9.9", "u",
                "[click](javascript:alert(1)) and [ok](https://github.com/o/r)");
        VersionInfo info = service("9.9.9", feed, true).snapshot();

        assertThat(info.getCurrentNotesHtml()).doesNotContain("javascript:");
        assertThat(info.getCurrentNotesHtml()).contains("https://github.com/o/r");
    }
}
