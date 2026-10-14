package com.qqmu.jync.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Gitee v5 API URL mapping, JSON parsing and the "bad config must not break boot" contract. */
class GiteeReleaseFeedTest {

    private static final String LATEST_JSON = """
            {
              "id": 1205002,
              "tag_name": "v1.7.2",
              "name": "Jync v1.7.2",
              "body": "# notes",
              "created_at": "2026-10-14T12:00:00+08:00",
              "assets": [
                {"name": "jync.jar",
                 "browser_download_url": "https://gitee.com/super_rgh/jync/releases/download/v1.7.2/jync.jar"},
                {"name": "v1.7.2.zip",
                 "browser_download_url": "https://gitee.com/super_rgh/jync/archive/refs/tags/v1.7.2.zip"}
              ]
            }
            """;

    @Test
    void mapsBrowserUrlsToApiBase() {
        assertThat(GiteeReleaseFeed.toApiBase("https://gitee.com/super_rgh/jync"))
                .isEqualTo("https://gitee.com/api/v5/repos/super_rgh/jync");
        assertThat(GiteeReleaseFeed.toApiBase("https://gitee.com/super_rgh/jync/"))
                .isEqualTo("https://gitee.com/api/v5/repos/super_rgh/jync");
        assertThat(GiteeReleaseFeed.toApiBase("http://gitee.com/super_rgh/jync"))
                .isEqualTo("https://gitee.com/api/v5/repos/super_rgh/jync");
    }

    @Test
    void rejectsGithubAndOtherHosts() {
        assertThatThrownBy(() -> GiteeReleaseFeed.toApiBase("https://github.com/vfaner/jync"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GiteeReleaseFeed.toApiBase("https://gitlab.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GiteeReleaseFeed.toApiBase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesLatestIncludingJarAssetAndTagsSource() throws Exception {
        ReleaseFeed.Release release =
                GiteeReleaseFeed.parse(LATEST_JSON, "super_rgh", "jync");

        assertThat(release.tag()).isEqualTo("v1.7.2");
        assertThat(release.source()).isEqualTo(ReleaseFeed.Source.GITEE);
        // Gitee has no html_url field: the browser release URL is derived so the page can
        // still link to it.
        assertThat(release.htmlUrl())
                .isEqualTo("https://gitee.com/super_rgh/jync/releases/tag/v1.7.2");
        assertThat(release.publishedAt()).isEqualTo(Instant.parse("2026-10-14T04:00:00Z"));
        assertThat(release.body()).isEqualTo("# notes");

        List<ReleaseFeed.Asset> assets = release.assets();
        assertThat(assets).hasSize(2);
        ReleaseFeed.Asset jar = release.jarAsset("jync.jar");
        assertThat(jar.name()).isEqualTo("jync.jar");
        assertThat(jar.url())
                .isEqualTo("https://gitee.com/super_rgh/jync/releases/download/v1.7.2/jync.jar");
        // Gitee API carries no size: verification must rely on zip integrity, not byte count.
        assertThat(jar.sizeBytes()).isNull();
    }

    @Test
    void missingAssetsYieldEmptyListAndNoJar() throws Exception {
        ReleaseFeed.Release release = GiteeReleaseFeed.parse(
                "{\"tag_name\":\"v1.0.0\",\"created_at\":null}", "o", "r");
        assertThat(release.tag()).isEqualTo("v1.0.0");
        assertThat(release.assets()).isEmpty();
        assertThat(release.jarAsset("jync.jar")).isNull();
        assertThat(release.htmlUrl()).isEqualTo("https://gitee.com/o/r/releases/tag/v1.0.0");
    }

    @Test
    void misconfiguredUrlDisablesChecksInsteadOfFailingConstruction() {
        GiteeReleaseFeed feed = new GiteeReleaseFeed("https://gitlab.example.com/a/b");
        assertThatThrownBy(feed::latest).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> feed.byTag("v1.1.1")).isInstanceOf(IllegalStateException.class);
    }
}
