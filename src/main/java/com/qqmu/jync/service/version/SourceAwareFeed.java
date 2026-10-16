package com.qqmu.jync.service.version;

import com.qqmu.jync.service.version.ReleaseFeed.Release;
import com.qqmu.jync.service.version.ReleaseFeed.Source;

/**
 * A release feed that can be pinned to one platform.
 *
 * <p>{@link ChainedReleaseFeed#latest()} reroutes GitHub → Gitee on failure, which is right
 * for the passive update check. The one-click upgrade is different: the user chose which
 * mirror downloads a 79MB jar, so a failure of the chosen mirror must surface as that
 * mirror's error instead of silently pulling bytes from somewhere else.
 */
public interface SourceAwareFeed {

    /** Queries exactly one platform; failures are reported, never rerouted. */
    Release latestFrom(Source source) throws Exception;

    /**
     * Queries exactly one platform for a specific tag. The upgrade uses this to cross-check a
     * download against the other mirror's checksum when the chosen mirror publishes none.
     *
     * <p>An implementation that cannot answer leaves the default, which throws: the caller
     * treats every failure here as "no checksum available" and never as a verification result.
     */
    default Release byTagFrom(Source source, String tag) throws Exception {
        throw new UnsupportedOperationException("byTagFrom is not supported by this feed");
    }
}
