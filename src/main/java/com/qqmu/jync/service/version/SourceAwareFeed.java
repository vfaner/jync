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
}
