package com.synctool.service.version;

import java.time.Instant;

/**
 * Source of published-release metadata. Split out from {@link VersionService} so tests can
 * feed canned releases without opening a socket.
 */
public interface ReleaseFeed {

    /** One release's relevant fields. */
    record Release(String tag, String htmlUrl, Instant publishedAt, String body) {
    }

    /** The newest published release (regardless of prerelease/draft flags the API applies). */
    Release latest() throws Exception;

    /** A specific release identified by its tag, e.g. {@code v1.1.1}. */
    Release byTag(String tag) throws Exception;
}
