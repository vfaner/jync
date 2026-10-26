package com.qqmu.jync.service.version;

/**
 * Decides whether the jar published under the newest tag is the one this JVM is running —
 * without downloading it.
 *
 * <p>Why this exists: the update check used to compare version numbers only, so a release
 * whose {@code jync.jar} was replaced under the same tag (v1.8.0 was re-uploaded three times)
 * left every host on the older bytes reporting "up to date" forever. The version says what the
 * code claims to be; only the artifact says which bytes those are.
 *
 * <p>{@link Result#UNKNOWN} is a first-class answer: an exploded run (IDE), a feed that
 * publishes no digest on a host that cannot be probed, or a failed probe must all degrade to
 * the old version-only behaviour rather than invent an update.
 */
public interface ArtifactComparison {

    enum Result {
        /** The published artifact is provably the same bytes as the running jar. */
        SAME,
        /** The published artifact is provably different from the running jar. */
        DIFFERENT,
        /** No comparison possible; callers fall back to comparing version numbers. */
        UNKNOWN
    }

    Result compare(ReleaseFeed.Release release);
}
