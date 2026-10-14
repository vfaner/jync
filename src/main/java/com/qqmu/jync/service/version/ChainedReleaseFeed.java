package com.qqmu.jync.service.version;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * GitHub-first release feed with an immediate Gitee fallback.
 *
 * <p>{@link #latest()} and {@link #byTag(String)} query GitHub first; ANY failure (timeout,
 * connection refused, non-200, malformed body) flips straight to the Gitee mirror instead of
 * painting the failure badge — for a China-hosted deployment GitHub being unreachable is the
 * common case, not an exceptional one. Only when both feeds fail does the caller learn it
 * (VersionService then caches UNKNOWN for a minute).
 *
 * <p>{@link #latestFrom(Source)} queries exactly one feed: the in-app upgrade dialog lets the
 * user choose a download mirror, and silently switching that choice would be dishonest about
 * where a 79MB jar came from.
 */
@Component
@Primary
@Slf4j
public class ChainedReleaseFeed implements ReleaseFeed, SourceAwareFeed {

    private final ReleaseFeed github;
    private final ReleaseFeed gitee;

    @Autowired
    public ChainedReleaseFeed(GithubReleaseFeed github, GiteeReleaseFeed gitee) {
        this((ReleaseFeed) github, (ReleaseFeed) gitee);
    }

    /** Test seam: stubs implement {@link ReleaseFeed} without sockets. */
    ChainedReleaseFeed(ReleaseFeed github, ReleaseFeed gitee) {
        this.github = github;
        this.gitee = gitee;
    }

    @Override
    public Release latest() throws Exception {
        try {
            return github.latest();
        } catch (Exception githubFailure) {
            log.debug("GitHub update check failed ({}), trying Gitee",
                    githubFailure.toString());
            try {
                return gitee.latest();
            } catch (Exception giteeFailure) {
                IllegalStateException both = new IllegalStateException(
                        "Update check failed on both feeds: GitHub=" + githubFailure
                                + ", GITEE=" + giteeFailure, giteeFailure);
                both.addSuppressed(githubFailure);
                throw both;
            }
        }
    }

    @Override
    public Release byTag(String tag) throws Exception {
        try {
            return github.byTag(tag);
        } catch (Exception githubFailure) {
            log.debug("GitHub tagged-release fetch failed ({}), trying Gitee",
                    githubFailure.toString());
            return gitee.byTag(tag);
        }
    }

    /**
     * Queries exactly one platform. Used by the one-click upgrade when the user has chosen
     * which mirror downloads the jar; a failure here is reported rather than rerouted.
     */
    @Override
    public Release latestFrom(Source source) throws Exception {
        return switch (source) {
            case GITHUB -> github.latest();
            case GITEE -> gitee.latest();
        };
    }
}
