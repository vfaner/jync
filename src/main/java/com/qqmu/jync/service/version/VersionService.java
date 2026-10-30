package com.qqmu.jync.service.version;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import org.springframework.boot.info.BuildProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Knows the running version and how it compares to the newest GitHub release.
 *
 * <p>The remote check never blocks a page render: it runs on a background thread (warmed up
 * once at boot and re-run when stale), results are cached (10 minutes on success, 1 minute on
 * failure), and an expired entry is served stale while being refreshed. On an offline intranet
 * host the settings page paints instantly with a "checking"/"failed" badge while the local
 * version and the bundled release notes — both read from the jar — render regardless.
 */
@Service
@Slf4j
public class VersionService {

    private static final Duration POSITIVE_TTL = Duration.ofMinutes(10);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final Supplier<String> currentVersionSupplier;
    private final ReleaseFeed feed;
    /** Per-mirror access for the click-triggered check; null in tests that only stub one feed. */
    private final SourceAwareFeed sourceAwareFeed;
    /** Whether the published jar is the running one; null when the caller wires no comparison. */
    private final ArtifactComparison artifactComparison;
    /** What an admin already acknowledged; null in tests that do not exercise dismissal. */
    private final ArtifactDismissal dismissal;
    private final boolean enabled;
    /** Runs the network call off the request thread; a fresh daemon thread per refresh. */
    private final Executor refreshExecutor;
    /** Guarantees a single in-flight check: page views never pile requests onto GitHub. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Parser markdownParser = Parser.builder().build();
    // escapeHtml blocks raw HTML in the notes; sanitizeUrls also drops javascript:/data:
    // link targets, which matters because the notes may come from the GitHub release body
    // (remote content) when the bundled copy is missing.
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    private volatile Cached cached;
    /** Lazily rendered notes for the running version, memoized (including the null case). */
    private volatile Notes notesCache;
    private volatile boolean notesLoaded;
    /**
     * Separate from {@code this} on purpose: {@link #refresh} holds the service monitor across
     * the remote check (up to two HTTP timeouts). Rendering notes under that same monitor made
     * the first settings page view wait out the whole round trip.
     */
    private final Object notesLock = new Object();

    @Autowired
    public VersionService(ObjectProvider<BuildProperties> buildProperties,
                          ReleaseFeed feed,
                          SourceAwareFeed sourceAwareFeed,
                          ArtifactComparison artifactComparison,
                          ArtifactDismissal dismissal,
                          @Value("${app.update-check.enabled:true}") boolean enabled) {
        this(() -> {
            BuildProperties props = buildProperties.getIfAvailable();
            if (props == null) {
                return null;
            }
            String version = props.getVersion();
            return StringUtils.hasText(version) ? version.trim() : null;
        }, feed, sourceAwareFeed, artifactComparison, dismissal, enabled, daemonExecutor());
    }

    /** Test seam: inject the "current" version and a fake feed; checks run inline. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed, boolean enabled) {
        this(currentVersionSupplier, feed, asSourceAware(feed), null, null, enabled,
                Runnable::run);
    }

    /** Test seam with a controllable executor so the non-blocking behavior is observable. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed, boolean enabled,
                   Executor refreshExecutor) {
        this(currentVersionSupplier, feed, asSourceAware(feed), null, null, enabled,
                refreshExecutor);
    }

    /** Test seam for the click-triggered check, which needs to see each mirror's answer. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed,
                   SourceAwareFeed sourceAwareFeed, boolean enabled) {
        this(currentVersionSupplier, feed, sourceAwareFeed, null, null, enabled, Runnable::run);
    }

    /** Test seam for the artifact comparison (same-version, re-published jar). */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed,
                   ArtifactComparison artifactComparison, boolean enabled) {
        this(currentVersionSupplier, feed, asSourceAware(feed), artifactComparison, null, enabled,
                Runnable::run);
    }

    /** Test seam for the dismissal of a re-published artifact. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed,
                   ArtifactComparison artifactComparison, ArtifactDismissal dismissal,
                   boolean enabled) {
        this(currentVersionSupplier, feed, asSourceAware(feed), artifactComparison, dismissal,
                enabled, Runnable::run);
    }

    /** Test seam for both seams at once: per-mirror answers plus an artifact comparison. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed,
                   SourceAwareFeed sourceAwareFeed, ArtifactComparison artifactComparison,
                   boolean enabled) {
        this(currentVersionSupplier, feed, sourceAwareFeed, artifactComparison, null, enabled,
                Runnable::run);
    }

    private static SourceAwareFeed asSourceAware(ReleaseFeed feed) {
        return feed instanceof SourceAwareFeed aware ? aware : null;
    }

    private VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed,
                           SourceAwareFeed sourceAwareFeed,
                           ArtifactComparison artifactComparison, ArtifactDismissal dismissal,
                           boolean enabled, Executor refreshExecutor) {
        this.currentVersionSupplier = currentVersionSupplier;
        this.feed = feed;
        this.sourceAwareFeed = sourceAwareFeed;
        this.artifactComparison = artifactComparison;
        this.dismissal = dismissal;
        this.enabled = enabled;
        this.refreshExecutor = refreshExecutor;
    }

    /** One short-lived daemon thread per check, so an idle service pins no pool on exit. */
    private static Executor daemonExecutor() {
        return task -> {
            Thread thread = new Thread(task, "jync-version-check");
            thread.setDaemon(true);
            thread.start();
        };
    }

    /** Warm the cache shortly after boot, off the startup path; failures just stay UNKNOWN. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            requestRefresh(currentVersionSupplier.get());
        }
    }

    public VersionInfo snapshot() {
        String current = currentVersionSupplier.get();

        if (!enabled) {
            Notes notes = currentNotes(current);
            return VersionInfo.builder()
                    .currentVersion(current)
                    .state(VersionInfo.State.DISABLED)
                    .currentNotesHtml(notes.html())
                    .currentNotesChinese(notes.chinese())
                    .build();
        }

        Cached c = cached;
        if (c == null || !c.validUntil.isAfter(Instant.now())) {
            // Either the boot warm-up is still in flight (c == null -> paint CHECKING) or the
            // entry expired: serve the stale one while a background check replaces it.
            requestRefresh(current);
            c = cached;
        }

        Notes notes = currentNotes(current);
        VersionInfo.VersionInfoBuilder builder = VersionInfo.builder()
                .currentVersion(current)
                .currentNotesHtml(notes.html())
                .currentNotesChinese(notes.chinese());
        if (c == null) {
            return builder.state(VersionInfo.State.CHECKING).build();
        }
        return builder
                .state(c.state)
                .latestTag(c.latestTag)
                .latestVersion(c.latestVersion)
                .releaseUrl(c.releaseUrl)
                .latestPublishedDate(c.latestPublishedDate)
                .latestArtifactDate(c.latestArtifactDate)
                .latestSource(c.latestSource)
                .build();
    }

    /**
     * One synchronous check for the settings page's nav badge: ask GitHub first, then Gitee.
     * GitHub is the canonical feed (and the only one whose API exposes download counts); the
     * Gitee mirror covers hosts that cannot reach github.com. Each feed is bounded by
     * {@code app.update-check.timeout-ms}, so a dead mirror costs one timeout rather than a
     * hung page. Never throws; when both mirrors fail — or the feature is switched off — the
     * caller learns nothing and shows nothing.
     */
    public UpdateCheck checkNow() {
        String current = currentVersionSupplier.get();
        if (!enabled) {
            return new UpdateCheck(false, false, current, null, null, null, false);
        }
        ReleaseFeed.Release latest;
        try {
            latest = latestGithubFirst();
        } catch (Exception bothFailed) {
            log.debug("Click-triggered update check failed: {}", bothFailed.toString());
            return new UpdateCheck(false, false, current, null, null, null, false);
        }
        String latestVersion = stripV(latest.tag());
        int cmp = current == null ? 1 : compareVersions(latestVersion, current);
        boolean newer = current != null && cmp > 0;
        // Same version, different bytes: the release was re-uploaded under an unchanged tag.
        // A version-number-only answer would call that "up to date" forever.
        boolean artifactUpdated = current != null && cmp == 0
                && artifactDiffers(latest) && !isDismissed(artifactKey(latest));
        return new UpdateCheck(true, newer || artifactUpdated, current, latestVersion,
                latest.tag(), trustedReleaseUrl(latest.htmlUrl()), artifactUpdated);
    }

    /**
     * GitHub first (canonical feed, and its API reports download counts), Gitee as the
     * fallback for hosts that cannot reach github.com.
     */
    private ReleaseFeed.Release latestGithubFirst() throws Exception {
        if (sourceAwareFeed == null) {
            return feed.latest();
        }
        try {
            return sourceAwareFeed.latestFrom(ReleaseFeed.Source.GITHUB);
        } catch (Exception githubFailure) {
            log.debug("GitHub update check failed ({}), trying Gitee", githubFailure.toString());
            return sourceAwareFeed.latestFrom(ReleaseFeed.Source.GITEE);
        }
    }

    /**
     * Result of a click-triggered check. {@code checked == false} means no mirror answered
     * (or the feature is off); the page then shows no badge at all.
     */
    public record UpdateCheck(boolean checked, boolean updateAvailable, String currentVersion,
                              String latestVersion, String latestTag, String releaseUrl,
                              boolean artifactUpdated) {
    }

    /** Submits a refresh unless one is already running; never throws into the request thread. */
    private void requestRefresh(String current) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refresh(current, Instant.now());
            } catch (Throwable t) {
                log.debug("Update check task failed: {}", t.toString());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private synchronized Cached refresh(String current, Instant now) {
        if (cached != null && cached.validUntil.isAfter(now)) {
            return cached; // Another thread won the race while we waited.
        }
        try {
            ReleaseFeed.Release latest = feed.latest();
            String latestVersion = stripV(latest.tag());
            VersionInfo.State state;
            if (current == null) {
                state = VersionInfo.State.UNKNOWN;
            } else {
                int cmp = compareVersions(latestVersion, current);
                if (cmp > 0) {
                    state = VersionInfo.State.UPDATE_AVAILABLE;
                } else if (cmp == 0 && artifactDiffers(latest)
                        && !isDismissed(artifactKey(latest))) {
                    state = VersionInfo.State.ARTIFACT_UPDATED;
                } else {
                    state = VersionInfo.State.UP_TO_DATE;
                }
            }
            cached = new Cached(state, latest.tag(), latestVersion,
                    trustedReleaseUrl(latest.htmlUrl()),
                    latest.publishedAt() == null ? null : DATE.format(latest.publishedAt()),
                    artifactDate(latest),
                    artifactKey(latest),
                    latest.source(),
                    now.plus(POSITIVE_TTL));
        } catch (Exception e) {
            log.debug("Update check failed: {}", e.toString());
            cached = new Cached(VersionInfo.State.UNKNOWN, null, null, null, null, null, null,
                    null, now.plus(NEGATIVE_TTL));
        }
        return cached;
    }

    /** True only when the feed says the published jar is provably not the running one. */
    private boolean artifactDiffers(ReleaseFeed.Release release) {
        if (artifactComparison == null) {
            return false;
        }
        try {
            return artifactComparison.compare(release) == ArtifactComparison.Result.DIFFERENT;
        } catch (RuntimeException e) {
            // The comparison is an optimization over the version check, never a failure path:
            // a probe that blows up just leaves the answer at "same version".
            log.debug("Artifact comparison failed: {}", e.toString());
            return false;
        }
    }

    /**
     * Remembers that the published package may be kept, silencing the same-version notice until
     * the publisher uploads different bytes. Returns false when nothing has been published yet
     * (no successful check), which the caller surfaces as "nothing to dismiss".
     */
    public boolean dismissArtifact() {
        Cached c = cached;
        if (dismissal == null || c == null || c.artifactKey == null) {
            return false;
        }
        try {
            dismissal.dismiss(c.artifactKey);
        } catch (RuntimeException e) {
            // A metadata-store hiccup must not answer the page with a 500; the reload that
            // follows simply shows the notice again.
            log.debug("Could not record the artifact dismissal: {}", e.toString());
            return false;
        }
        if (c.state == VersionInfo.State.ARTIFACT_UPDATED) {
            // The page reloads immediately after dismissing; serving the stale ARTIFACT_UPDATED
            // entry would light the notice right back up.
            cached = new Cached(VersionInfo.State.UP_TO_DATE, c.latestTag, c.latestVersion,
                    c.releaseUrl, c.latestPublishedDate, c.latestArtifactDate, c.artifactKey,
                    c.latestSource, c.validUntil);
        }
        return true;
    }

    /** True when this exact published artifact was already acknowledged. */
    private boolean isDismissed(String artifactKey) {
        if (dismissal == null || artifactKey == null) {
            return false;
        }
        try {
            return artifactKey.equals(dismissal.dismissedKey());
        } catch (RuntimeException e) {
            log.debug("Could not read the artifact dismissal: {}", e.toString());
            return false;
        }
    }

    /** Identity of the published jar, or null when the release carries no such asset. */
    private static String artifactKey(ReleaseFeed.Release release) {
        ReleaseFeed.Asset jar = release.jarAsset(ReleaseFeed.JAR_ASSET_NAME);
        return jar == null ? null : jar.identity();
    }

    /** Upload date of the published jar, or null when the feed does not report one. */
    private static String artifactDate(ReleaseFeed.Release release) {
        ReleaseFeed.Asset jar = release.jarAsset(ReleaseFeed.JAR_ASSET_NAME);
        return jar == null || jar.updatedAt() == null ? null : DATE.format(jar.updatedAt());
    }

    /** Rendered notes plus whether they were written in Chinese; {@code NONE} when absent. */
    private record Notes(String html, boolean chinese) {
        static final Notes NONE = new Notes(null, false);
    }

    /** Any Han character at all: the notes are single-language prose, not mixed labels. */
    private static boolean isChinese(String text) {
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /**
     * Release notes for the running version as safe HTML. Prefer the markdown bundled at
     * build time (works on offline hosts); fall back to the tagged GitHub release body.
     */
    private Notes currentNotes(String current) {
        if (!StringUtils.hasText(current)) {
            return Notes.NONE;
        }
        if (notesLoaded) {
            return notesCache;
        }
        synchronized (notesLock) {
            if (notesLoaded) {
                return notesCache;
            }
            String markdown = null;
            String bundled = "release-notes/RELEASE_NOTES_v" + current + ".md";
            try {
                ClassPathResource resource = new ClassPathResource(bundled);
                if (resource.exists()) {
                    markdown = StreamUtils.copyToString(resource.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.debug("Bundled release notes unreadable: {}", bundled, e);
            }
            if (!StringUtils.hasText(markdown)) {
                try {
                    markdown = feed.byTag("v" + current).body();
                } catch (Exception e) {
                    log.debug("No GitHub release notes for v{}: {}", current, e.toString());
                }
            }
            notesCache = StringUtils.hasText(markdown)
                    ? new Notes(htmlRenderer.render(markdownParser.parse(markdown)),
                            isChinese(markdown))
                    : Notes.NONE;
            notesLoaded = true;
            return notesCache;
        }
    }

    /**
     * The release link is rendered straight into {@code th:href} and comes from the feed's
     * JSON. Only an https github.com / gitee.com URL may reach the page — anything else,
     * including a {@code javascript:} target or an http downgrade, is dropped and the
     * template renders no link.
     */
    static String trustedReleaseUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        return (u.startsWith("https://github.com/") || u.startsWith("https://gitee.com/"))
                ? u : null;
    }

    /** Strips one leading {@code v}: {@code v1.1.1} -> {@code 1.1.1}. */
    public static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /**
     * Compares dotted numeric versions segment by segment ({@code 1.2.10} &gt; {@code 1.2.9});
     * missing segments and non-numeric parts count as zero.
     *
     * <p>Segments are compared as {@link java.math.BigInteger}: date-stamped tags like
     * {@code 20250923120000} overflow an int, and a parseInt failure here would surface as
     * a permanently UNKNOWN update state rather than an error anyone would notice.
     */
    public static int compareVersions(String a, String b) {
        String[] sa = stripV(a).split("[^0-9A-Za-z]+");
        String[] sb = stripV(b).split("[^0-9A-Za-z]+");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            java.math.BigInteger na = numericSegment(i < sa.length ? sa[i] : null);
            java.math.BigInteger nb = numericSegment(i < sb.length ? sb[i] : null);
            int cmp = na.compareTo(nb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static java.math.BigInteger numericSegment(String part) {
        if (part == null || part.isBlank()) {
            return java.math.BigInteger.ZERO;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.isEmpty()
                ? java.math.BigInteger.ZERO
                : new java.math.BigInteger(digits.toString());
    }

    private record Cached(VersionInfo.State state, String latestTag, String latestVersion,
                          String releaseUrl, String latestPublishedDate,
                          String latestArtifactDate, String artifactKey,
                          ReleaseFeed.Source latestSource, Instant validUntil) {
    }
}
