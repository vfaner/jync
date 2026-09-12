package com.synctool.service.version;

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
    private final boolean enabled;
    /** Runs the network call off the request thread; a fresh daemon thread per refresh. */
    private final Executor refreshExecutor;
    /** Guarantees a single in-flight check: page views never pile requests onto GitHub. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    private volatile Cached cached;
    /** Lazily rendered notes for the running version, memoized (including the null case). */
    private volatile String notesCache;
    private volatile boolean notesLoaded;

    @Autowired
    public VersionService(ObjectProvider<BuildProperties> buildProperties,
                          ReleaseFeed feed,
                          @Value("${app.update-check.enabled:true}") boolean enabled) {
        this(() -> {
            BuildProperties props = buildProperties.getIfAvailable();
            if (props == null) {
                return null;
            }
            String version = props.getVersion();
            return StringUtils.hasText(version) ? version.trim() : null;
        }, feed, enabled, daemonExecutor());
    }

    /** Test seam: inject the "current" version and a fake feed; checks run inline. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed, boolean enabled) {
        this(currentVersionSupplier, feed, enabled, Runnable::run);
    }

    /** Test seam with a controllable executor so the non-blocking behavior is observable. */
    VersionService(Supplier<String> currentVersionSupplier, ReleaseFeed feed, boolean enabled,
                   Executor refreshExecutor) {
        this.currentVersionSupplier = currentVersionSupplier;
        this.feed = feed;
        this.enabled = enabled;
        this.refreshExecutor = refreshExecutor;
    }

    /** One short-lived daemon thread per check, so an idle service pins no pool on exit. */
    private static Executor daemonExecutor() {
        return task -> {
            Thread thread = new Thread(task, "synctool-version-check");
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
            return VersionInfo.builder()
                    .currentVersion(current)
                    .state(VersionInfo.State.DISABLED)
                    .currentNotesHtml(currentNotes(current))
                    .build();
        }

        Cached c = cached;
        if (c == null || !c.validUntil.isAfter(Instant.now())) {
            // Either the boot warm-up is still in flight (c == null -> paint CHECKING) or the
            // entry expired: serve the stale one while a background check replaces it.
            requestRefresh(current);
            c = cached;
        }

        VersionInfo.VersionInfoBuilder builder = VersionInfo.builder()
                .currentVersion(current)
                .currentNotesHtml(currentNotes(current));
        if (c == null) {
            return builder.state(VersionInfo.State.CHECKING).build();
        }
        return builder
                .state(c.state)
                .latestTag(c.latestTag)
                .latestVersion(c.latestVersion)
                .releaseUrl(c.releaseUrl)
                .latestPublishedDate(c.latestPublishedDate)
                .build();
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
                state = compareVersions(latestVersion, current) > 0
                        ? VersionInfo.State.UPDATE_AVAILABLE
                        : VersionInfo.State.UP_TO_DATE;
            }
            cached = new Cached(state, latest.tag(), latestVersion, latest.htmlUrl(),
                    latest.publishedAt() == null ? null : DATE.format(latest.publishedAt()),
                    now.plus(POSITIVE_TTL));
        } catch (Exception e) {
            log.debug("Update check failed: {}", e.toString());
            cached = new Cached(VersionInfo.State.UNKNOWN, null, null, null, null,
                    now.plus(NEGATIVE_TTL));
        }
        return cached;
    }

    /**
     * Release notes for the running version as safe HTML. Prefer the markdown bundled at
     * build time (works on offline hosts); fall back to the tagged GitHub release body.
     */
    private String currentNotes(String current) {
        if (!StringUtils.hasText(current)) {
            return null;
        }
        if (notesLoaded) {
            return notesCache;
        }
        synchronized (this) {
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
                    ? htmlRenderer.render(markdownParser.parse(markdown))
                    : null;
            notesLoaded = true;
            return notesCache;
        }
    }

    /** Strips one leading {@code v}: {@code v1.1.1} -> {@code 1.1.1}. */
    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /**
     * Compares dotted numeric versions segment by segment ({@code 1.2.10} &gt; {@code 1.2.9});
     * missing segments and non-numeric parts count as zero.
     */
    static int compareVersions(String a, String b) {
        String[] sa = stripV(a).split("[^0-9A-Za-z]+");
        String[] sb = stripV(b).split("[^0-9A-Za-z]+");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int na = numericSegment(i < sa.length ? sa[i] : null);
            int nb = numericSegment(i < sb.length ? sb[i] : null);
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static int numericSegment(String part) {
        if (part == null || part.isBlank()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    private record Cached(VersionInfo.State state, String latestTag, String latestVersion,
                          String releaseUrl, String latestPublishedDate, Instant validUntil) {
    }
}
