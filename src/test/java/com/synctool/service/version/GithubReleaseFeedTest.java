package com.synctool.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** URL→API-base mapping and the "bad config must not break boot" contract. */
class GithubReleaseFeedTest {

    @Test
    void mapsBrowserUrlsToApiBase() {
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/synctool"))
                .isEqualTo("https://api.github.com/repos/vfaner/synctool");
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/synctool/"))
                .isEqualTo("https://api.github.com/repos/vfaner/synctool");
        assertThat(GithubReleaseFeed.toApiBase("https://github.com/vfaner/synctool.git"))
                .isEqualTo("https://api.github.com/repos/vfaner/synctool");
        assertThat(GithubReleaseFeed.toApiBase("http://github.com/vfaner/synctool"))
                .isEqualTo("https://api.github.com/repos/vfaner/synctool");
    }

    @Test
    void mapsSshCloneUrl() {
        assertThat(GithubReleaseFeed.toApiBase("git@github.com:vfaner/synctool.git"))
                .isEqualTo("https://api.github.com/repos/vfaner/synctool");
    }

    @Test
    void rejectsOtherHostsAndGarbage() {
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase("https://gitlab.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubReleaseFeed.toApiBase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void misconfiguredUrlDisablesChecksInsteadOfFailingConstruction() {
        // The bean is created even when app.github-url points elsewhere; every fetch then fails
        // closed so a typo cannot stop the application from starting.
        GithubReleaseFeed feed = new GithubReleaseFeed("https://gitlab.example.com/a/b");
        assertThatThrownBy(feed::latest).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> feed.byTag("v1.1.1")).isInstanceOf(IllegalStateException.class);
    }
}
