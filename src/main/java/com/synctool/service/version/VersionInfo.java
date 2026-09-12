package com.synctool.service.version;

import lombok.Builder;
import lombok.Getter;

/**
 * Everything the settings page needs to describe the running version relative to the newest
 * published release. Immutable; produced fresh by {@link VersionService#snapshot()} from a
 * short-lived cache.
 */
@Getter
@Builder
public class VersionInfo {

    public enum State {
        /** Running version equals the newest release. */
        UP_TO_DATE,
        /** A newer release exists on the remote. */
        UPDATE_AVAILABLE,
        /** First check after boot has not completed yet; nothing is being blocked on it. */
        CHECKING,
        /** Remote could not be reached or parsed (offline intranet, rate limit, bad URL). */
        UNKNOWN,
        /** Update check switched off via {@code app.update-check.enabled=false}. */
        DISABLED
    }

    /** Artifact version without the "v" prefix, e.g. {@code 1.1.1}; {@code null} for dev runs. */
    private final String currentVersion;
    /** Newest release version without "v"; {@code null} when unknown/disabled. */
    private final String latestVersion;
    /** Newest release tag, e.g. {@code v1.1.2}; used for links and notes lookups. */
    private final String latestTag;
    /** Browser URL of the newest release page. */
    private final String releaseUrl;
    /** Publish date of the newest release, {@code yyyy-MM-dd}; may be null. */
    private final String latestPublishedDate;
    private final State state;
    /** Rendered release notes for the CURRENT version (safe HTML), or null when unavailable. */
    private final String currentNotesHtml;
}
